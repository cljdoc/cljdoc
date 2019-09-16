(ns cljdoc.server.search.artifact-indexer
  (:require
    [cljdoc.spec :as cljdoc-spec]
    [clojure.edn :as edn]
    [clojure.spec.alpha :as s]
    [clojure.java.io :as io]
    [clj-http.lite.client :as http]
    [clojure.tools.logging :as log]
    [cheshire.core :as json])
  (:import (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.index IndexWriterConfig IndexWriterConfig$OpenMode IndexWriter Term IndexOptions)
           (org.apache.lucene.document Document StringField Field$Store TextField FieldType Field)
           (org.apache.lucene.analysis CharArraySet)
           (org.apache.lucene.analysis.miscellaneous PerFieldAnalyzerWrapper)
           (org.apache.lucene.analysis.core StopAnalyzer)
           (java.util.zip GZIPInputStream)
           (java.io InputStream)
           (org.apache.lucene.store FSDirectory)
           (java.nio.file Paths)))

(defn ^FSDirectory fsdir [index-dir] ;; BEWARE: Duplicated in artifact-indexer and search ns
  (FSDirectory/open (Paths/get index-dir (into-array String nil))))

(defn format-maven-central-resp [json]
  (when (> (-> json :response :numFound) 200)
    (log/error "Maven Central has more results than our limit of 200 => "
               "increase the limit or implement pagination to get them all"))
  (->> (get-in json [:response :docs])
       (map (fn [{:keys [a g latestVersion]}]
              {:artifact-id a
               ;; TODO Add a field indicating that these results should be boosted
               :group-id g
               :versions [latestVersion]
               ;; We do not have description so fake one with g and a so
               ;; that it will match of this field too and score higher
               :description (str "Clojure Contrib library " g "/" a)
               :origin :maven-central}))))

(def ^:private maven-artefacts ["org.clojure"
                                "com.turtlequeue"])
(defn maven-search-url []
  (let [q (->> (map #(str "g:" %) maven-artefacts)
               (string/join "+OR+"))]
    (str "http://search.maven.org/solrsearch/select?q=" q "&rows=200")))

(defn load-maven-central-artifacts []
  ;; GET http://search.maven.org/solrsearch/select?q=g:org.clojure -> JSON .response.docs[] = {g: group, a: artifact-id, latestVersion, versionCount
  (try
    (with-open [in (io/reader (maven-search-url))]
      (format-maven-central-resp
        (json/parse-stream in keyword)))
    (catch Exception e
      (log/info e "Failed to download artifacts from Maven Central")
      nil)))

(s/fdef load-maven-central-artifacts
        :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(defonce clojars-last-modified (atom nil))

(defn process-clojars-response [{:keys [headers body]}]
  {:pre [(instance? InputStream body)]}
  (with-open [in (io/reader (GZIPInputStream. body))]
    (let [artifacts     (into []
                              (comp
                                (map #(-> % edn/read-string (assoc :origin :clojars)))
                                ;; Latest Clojure Contrib libs are in Maven Central
                                ;; and thus should be loaded from there
                                (filter #(not= "org.clojure" (:group-id %))))
                              (line-seq in))
          last-modified (get headers "last-modified")]
      (log/debug (str "Downloaded " (count artifacts) " artifacts from Clojars with last-modified " last-modified))
      (reset! clojars-last-modified last-modified)
      artifacts)))

(defn load-clojars-artifacts [force?]
  (try
    (let [res
          (http/get
            "https://clojars.org/repo/feed.clj.gz"
            {:throw-exceptions false
             :as               :stream
             :headers          {"If-Modified-Since" (when-not force? @clojars-last-modified)
                                ;; Avoid double-gzipping by Clojars' proxy:
                                "Accept-Encoding" ""}})]
      (case (:status res)
        304 (do
              (log/debug
                (str
                  "Skipping Clojars download - no change since last one at "
                  @clojars-last-modified))
              nil) ;; data not changed since the last time, do nothing
        200 (process-clojars-response res)
        (throw (ex-info "Unexpected HTTP status from Clojars" {:response res}))))
    (catch Exception e
      (log/info e "Failed to download artifacts from Clojars")
      nil)))

(s/fdef load-clojars-artifacts
        :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(defn ^String artifact->id [{:keys [artifact-id group-id]}]
  (str group-id ":" artifact-id))

(defn unsearchable-stored-field [^String name ^String value]
  (let [type (doto (FieldType.)
               (.setOmitNorms true)
               (.setIndexOptions (IndexOptions/NONE))
               (.setTokenized false)
               (.setStored true)
               (.freeze))]
    (Field.
      name
      value
      type)))


(defn add-versions [doc versions]
  (run!
    #(.add doc
           (unsearchable-stored-field "versions" %))
    versions))

(defn ^Iterable artifact->doc
  [{:keys [^String artifact-id
           ^String group-id
           ^String description
           ^String origin
           versions]
    :or {description "", origin "N/A"}
    :as artifact}]
  (doto (Document.)
    ;; *StringField* is indexed but not tokenized, term freq. or positional info not indexed
    ;; id: We need a unique identifier for each doc so that we can use updateDocument
    (.add (StringField. "id" (artifact->id artifact) Field$Store/YES))
    (.add (StringField. "origin" ^String (name origin) Field$Store/YES))
    (.add (TextField. "artifact-id" artifact-id Field$Store/YES))
    ;; Keep also un-tokenized version of the id for RegExp searches (Better to replace with
    ;; a custom tokenizer that produces both the original + individual tokens)
    (.add (StringField. "artifact-id-raw" artifact-id Field$Store/YES))
    (.add (TextField. "group-id" group-id Field$Store/YES))
    (.add (TextField. "group-id-packages" group-id Field$Store/YES))
    (.add (StringField. "group-id-raw" group-id Field$Store/YES))
    (.add (TextField. "description" description Field$Store/YES))
    (add-versions versions)))

(defn index-artifacts [^IndexWriter idx-writer artifacts create?]
  (run!
    (fn [artifact]
      (if create?
        (.addDocument idx-writer (artifact->doc artifact))
        (.updateDocument
          idx-writer
          (Term. "id" (artifact->id artifact))
          (artifact->doc artifact))))

    artifacts))

(defn mk-indexing-analyzer []
  (PerFieldAnalyzerWrapper.
    (StandardAnalyzer.)
    ;; StandardAnalyzer does not break at . as in 'org.clojure':
    {"group-id"          (StopAnalyzer. (CharArraySet. ["." "-"] true))
     ;; Group ID with token per package component, not breaking at '-' this
     ;; time so that 'clojure' will match 'org.clojure' better than 'org-clojure':
     "group-id-packages" (StopAnalyzer. (CharArraySet. ["."] true))}))

(defn index! [^String index-dir artifacts]
  (let [create?   false ;; update
        analyzer (mk-indexing-analyzer)
        iw-cfg   (doto (IndexWriterConfig. analyzer)
                   (.setOpenMode (if create?
                                   IndexWriterConfig$OpenMode/CREATE
                                   IndexWriterConfig$OpenMode/CREATE_OR_APPEND))
                   ;; Increase RAM for speed up. BEWARE: Increase also JVM heap size: -Xmx512m or -Xmx1g
                   (.setRAMBufferSizeMB 256.0))
        idx-dir  (fsdir index-dir)]
    (with-open [idx-writer (IndexWriter. idx-dir iw-cfg)]
      (index-artifacts
        idx-writer
        artifacts
        create?))))

(defn download-and-index!
  ([^String index-dir] (download-and-index! index-dir false))
  ([^String index-dir force?]
   (log/info "Download & index starting...")
   (index! index-dir (into
                       (load-clojars-artifacts force?)
                       (load-maven-central-artifacts)))))

(defn index-artifact [^String index-dir artifact]
  (index! index-dir [artifact]))

(s/fdef index-artifact
        :args (s/cat :artifact ::cljdoc-spec/artifact))

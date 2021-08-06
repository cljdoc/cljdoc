(ns cljdoc.server.search.artifact-indexer
  (:require
   [cljdoc.spec :as cljdoc-spec]
   [clojure.edn :as edn]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clj-http.lite.client :as http]
   [clojure.tools.logging :as log]
   [cheshire.core :as json]
   [robert.bruce :as rb])
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

;;------------------------------------------------------------------------------------------------------- Maven Central

(def ^:private maven-groups ["org.clojure"
                             "com.turtlequeue"])

(def ^:private maven-grp-version-counts
  "group-id -> number of different artifact+version combinations in the group.
  Used to find out whether there is any new stuff => refetch needed."
  (atom nil))

(comment
  (reset! maven-grp-version-counts nil))

(defn fetch-body [url]
  (try
    (rb/try-try-again
     {:sleep 500
      :decay :double
      :tries 3
      :catch Throwable}
     #(-> url
          (http/get {:as :stream :throw-exceptions true})
          :body
          io/input-stream
          io/reader))
    (catch Exception e
      (log/info e "Failed to download artifacts from url")
      nil)))

(defn fetch-json [url]
  (when-let [body (fetch-body url)]
    (json/parse-stream body keyword)))

(defn fetch-maven-docs
  "Fetch documents matching the query from Maven Central; supports pagination."
  [query]
  (loop [start 0, acc nil]
    (let [rows 200
          latest-version-only? false

          {total :numFound, docs :docs}
          (:response (fetch-json
                      (str "http://search.maven.org/solrsearch/select?q=" query
                           "&start=" start "&rows=" rows
                           (when-not latest-version-only? "&core=gav"))))

          more? (pos? (- total start rows))
          acc'  (concat acc docs)]
      (if more?
        (recur (+ start rows) acc')
        acc'))))

(defn fetch-maven-description
  "Fetch the description of a Maven Central artifact (if it has any)"
  [{:keys [artifact-id group-id], [version] :versions}]
  (let [g-path (str/replace group-id "." "/")
        url (str "https://search.maven.org/remotecontent?filepath=" g-path "/" artifact-id "/" version "/" artifact-id "-" version ".pom")]
    (rb/try-try-again
     {:sleep 500
      :decay :double
      :tries 3
      :catch Throwable}
     #(->> (fetch-body url)
           line-seq
           (some (fn [l] (re-find #"<description>(.*)</description>" l)))
           second))))

(defn add-description! [{a :artifact-id, g :group-id :as artifact}]
  (assoc artifact
         :description
         (or
          (fetch-maven-description artifact)
          (str "Maven Central Clojure library " g "/" a))))

(defn maven-doc->artifact [{:keys [g a v #_versionCount #_timestamp]}]
  {:artifact-id a
   :group-id g
   :version v
   ;; We do not have description so fake one with g and a so
   ;; that it will match of this field too and score higher
   ;; Description possible from https://search.maven.org/remotecontent?filepath=org/clojure/clojure/1.10.1/clojure-1.10.1.pom
   ;; -> `<description>...</description>`
   :origin :maven-central})

(defn new-artifacts?
  "Are the new artifacts or versions in Maven Central within this group?
  Maven Central does not support ETAG / If-Modified-Since so we use the artifacts+versions count as an
  indication that there is something new and we need to re-fetch."
  [group-id]
  (let [versions-cnt (-> (fetch-json (str "http://search.maven.org/solrsearch/select?q=g:" group-id "&core=gav&rows=0"))
                         :response :numFound)]
    (> versions-cnt (get @maven-grp-version-counts group-id -1))))

(defn update-grp-version-count! [group-id group-docs]
  (swap! maven-grp-version-counts assoc group-id (count group-docs))
  group-docs)

(defn mvn-merge-versions [artifacts]
  (->> artifacts
       ;; NOTE Different artifacts are interleaved but in total newer versions it seems come boefore older ones of any given artifact
       ;; so we cannot use `partition` but `group-by` (which preserves order) works perfectly
       (group-by (juxt :group-id :artifact-id))
       vals
       (map (fn [versions]
              (-> (first versions)
                  (dissoc :version)
                  (assoc :versions (map :version versions)))))))

(defn load-maven-central-artifacts-for [group-id force?]
  (when (or force? (new-artifacts? group-id))
    (->> (fetch-maven-docs (str "g:" group-id))
         (update-grp-version-count! group-id)
         (map maven-doc->artifact)
         (mvn-merge-versions)
         (pmap add-description!))))

(defn load-maven-central-artifacts
  "Load artifacts from Maven Central - if there are any new ones (or `force?`)
  NOTE: Takes ± 2s as of 11/2019"
  [force?]
  (mapcat #(load-maven-central-artifacts-for % force?) maven-groups))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

;;------------------------------------------------------------------------------------------------------------- Clojars

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

;;-------------------------------------------------------------------------------------------------------------

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
  (let [create?   false ;; => update, see how we use `.updateDocument` above
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
   (let [result (index! index-dir (into
                                   (load-clojars-artifacts force?)
                                   (load-maven-central-artifacts force?)))]
     (log/info "Finished downloading & indexing.")
     result)))

(defn index-artifact [^String index-dir artifact]
  (index! index-dir [artifact]))

(s/fdef index-artifact
  :args (s/cat :index-dir string? :artifact ::cljdoc-spec/artifact))

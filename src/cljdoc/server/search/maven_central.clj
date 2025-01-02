(ns cljdoc.server.search.maven-central
  "Fetch listing of artifacts from maven central."
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [cljdoc-shared.pom :as pom]
   [cljdoc.spec :as cljdoc-spec]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [robert.bruce :as rb]))

;; There are not many clojars libraries on maven central.
;; We'll manualy adjust this list for now:
(def ^:private maven-groups ["org.clojure"
                             "io.github.clojure"
                             "com.turtlequeue"])

(def ^:private maven-grp-version-counts
  "group-id -> number of different artifact+version combinations in the group.
  Used to find out whether there is any new stuff => refetch needed."
  (atom nil))

(defn- fetch-body [url]
  (let [max-tries 10]
    (try
      (rb/try-try-again
       {:sleep 500
        :decay :double
        :tries max-tries
        :catch Throwable}
       #(do
          (when (not rb/*first-try*)
            (log/errorf rb/*error*  "Try %d of %d: %s" (dec rb/*try*) max-tries url))
          (-> url
              (http/get {:as :stream :throw true})
              :body
              io/input-stream
              io/reader)))
      (catch Exception e
        (log/infof e "Failed to download maven artifacts from %s after %d tries" url max-tries)
        nil))))

(defn- fetch-json [url]
  (when-let [body (fetch-body url)]
    (json/parse-stream body keyword)))

(defn- fetch-maven-docs
  "Fetch documents matching the query from Maven Central; supports pagination."
  [query]
  (loop [start 0, acc nil]
    (let [rows 200
          opt-get-all-versions "&core=gav"

          {total :numFound, docs :docs}
          (:response (fetch-json
                      (str "https://search.maven.org/solrsearch/select?q=" query
                           "&start=" start "&rows=" rows
                           opt-get-all-versions)))

          more? (pos? (- total start rows))
          acc'  (concat acc docs)]
      (if more?
        (recur (+ start rows) acc')
        acc'))))

(defn- fetch-maven-description
  "Fetch the description of a Maven Central artifact (if it has any)"
  [{:keys [artifact-id group-id], [version] :versions}]
  (let [g-path (str/replace group-id "." "/")
        url (str "https://search.maven.org/remotecontent?filepath=" g-path "/" artifact-id "/" version "/" artifact-id "-" version ".pom")]
    (->> (fetch-body url)
         slurp
         pom/parse
         :artifact-info
         :description)))

(defn- add-description! [artifact]
  (if-let [d (fetch-maven-description artifact)]
    (assoc artifact :description d)
    artifact))

(defn- maven-doc->artifact [{:keys [g a v #_versionCount #_timestamp]}]
  {:artifact-id a
   :group-id g
   :version v
   ;; We do not have description so fake one with g and a so
   ;; that it will match of this field too and score higher
   ;; Description possible from https://search.maven.org/remotecontent?filepath=org/clojure/clojure/1.10.1/clojure-1.10.1.pom
   ;; -> `<description>...</description>`
   :origin :maven-central})

(defn- new-artifacts?
  "Are the new artifacts or versions in Maven Central within this group?
  Maven Central does not support ETAG / If-Modified-Since so we use the artifacts+versions count as an
  indication that there is something new and we need to re-fetch."
  [group-id]
  (let [versions-cnt (-> (fetch-json (str "https://search.maven.org/solrsearch/select?q=g:" group-id "&core=gav&rows=0"))
                         :response :numFound)]
    (> versions-cnt (get @maven-grp-version-counts group-id -1))))

(defn- update-grp-version-count! [group-id group-docs]
  (swap! maven-grp-version-counts assoc group-id (count group-docs))
  group-docs)

(defn- mvn-merge-versions [artifacts]
  (->> artifacts
       ;; NOTE Different artifacts are interleaved but in total newer versions it seems come before older ones of any given artifact
       ;; so we cannot use `partition` but `group-by` (which preserves order) works perfectly
       (group-by (juxt :group-id :artifact-id))
       vals
       (map (fn [versions]
              (-> (first versions)
                  (dissoc :version)
                  (assoc :versions (map :version versions)))))))

(defn- load-maven-central-artifacts-for [group-id force?]
  (when (or force? (let [new? (new-artifacts? group-id)]
                     (when (not new?)
                       (log/infof "Skipping Maven download for %s, no change detected" group-id))
                     new?))
    (->> (fetch-maven-docs (str "g:" group-id))
         (update-grp-version-count! group-id)
         (map maven-doc->artifact)
         (mvn-merge-versions)
         (pmap add-description!))))

(defn load-maven-central-artifacts
  "Load artifacts from Maven Central - if there are any new ones (or `force?`)
  NOTE: Takes ± 2s as of 11/2019"
  [force?]
  (let [artifacts (mapcat #(load-maven-central-artifacts-for % force?) maven-groups)]
    (log/infof "Downloaded %d artifacts from Maven Central" (count artifacts))
    artifacts))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(comment
  (fetch-maven-description {:group-id "org.clojure" :artifact-id "clojurescript" :versions ["1.11.5"]})

  (def artifacts (load-maven-central-artifacts false))

  (count artifacts)
  ;; => 79

  maven-grp-version-counts
  ;; => #<Atom@22dc8bf8: {"org.clojure" 1997, "com.turtlequeue" 27}>

  (load-maven-central-artifacts true)

  (reset! maven-grp-version-counts nil)

  :eoc)

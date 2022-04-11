(ns cljdoc.server.search.maven-central
  "Fetch listing of artifacts from maven central."
  (:require
   [cheshire.core :as json]
   [clj-http.lite.client :as http]
   [cljdoc.spec :as cljdoc-spec]
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [robert.bruce :as rb]))

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
      :tries 10
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
    (->> (fetch-body url)
         line-seq
         (some (fn [l] (re-find #"<description>(.*)</description>" l)))
         second)))

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

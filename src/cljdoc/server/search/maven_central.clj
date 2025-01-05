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

(defn- fetch-body [ctx url]
  (let [max-tries 10]
    (try
      (rb/try-try-again
       {:sleep 500
        :decay :double ; max 9 retries means: .5s then 1s, 2s 4s 8s 16s 32s 64s 128s
        :tries max-tries
        :catch Throwable}
       #(do
          (when (not rb/*first-try*)
            (log/errorf rb/*error*  "Try %d of %d: %s" (dec rb/*try*) max-tries url))
          (swap! ctx update :requests conj url)
          (-> url
              (http/get {:as :stream :throw true})
              :body
              io/input-stream
              io/reader)))
      (catch Exception e
        (log/infof e "Failed to download maven artifacts from %s after %d tries" url max-tries)
        nil))))

(defn- fetch-json [ctx url]
  (when-let [body (fetch-body ctx url)]
    (json/parse-stream body keyword)))

(defn- fetch-maven-docs
  "Fetch documents matching the query from Maven Central; requires pagination."
  [ctx query]
  (loop [start 0, acc nil]
    (let [rows 200 ;; maximum rows per page, if exceeded will default to 20
          opt-get-all-versions "&core=gav"

          {total :numFound, docs :docs}
          (:response (fetch-json
                      ctx
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
  [ctx {:keys [artifact-id group-id], [version] :versions}]
  (let [g-path (str/replace group-id "." "/")
        url (str "https://search.maven.org/remotecontent?filepath=" g-path "/" artifact-id "/" version "/" artifact-id "-" version ".pom")]
    (->> (fetch-body ctx url)
         slurp
         pom/parse
         :artifact-info
         :description)))

(defn- add-description [ctx artifact]
  (if-let [d (fetch-maven-description ctx artifact)]
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
  [ctx group-id]
  (let [versions-cnt (-> (fetch-json ctx (str "https://search.maven.org/solrsearch/select?q=g:" group-id "&core=gav&rows=0"))
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

(defn- load-maven-central-artifacts-for [ctx group-id force?]
  (when (or force? (let [new? (new-artifacts? ctx group-id)]
                     (when (not new?)
                       (log/infof "Skipping Maven download for %s, no change detected" group-id))
                     new?))
    (->> (fetch-maven-docs ctx (str "g:" group-id))
         (update-grp-version-count! group-id)
         (map maven-doc->artifact)
         (mvn-merge-versions)
         (mapv #(add-description ctx %)))))

(defn load-maven-central-artifacts
  "Load artifacts from Maven Central - if there are any new ones (or `force?` `true` when testing).
  The Maven Central team has expressed an interest folks minimizing requests when hitting their APIs so we
  make some attempts to do so.

  We make:
  - a single request per group-id to test for any new artifact versions within the group
  - we don't know what has changed in the group-id, so we request all artifact versions in that group (these are paged requests)
  - fetch the most recent description for each artifact in the group

  At the time of this writing (Jan-2025):
  - we check once each hour
  - we check 3 groups, so this means a minimum of 3 requests
  - when all groups need updating we have a total of 93 requests (this will always happen at startup)
  - it is very rare that a group will have a new artifact, so typically there will be 3 requests per hour

  This puts us well under the threshold of 1000 requests in a span of 5 minutes.

  We could cache descriptions and not refetch if there is no change in versions for an artifact, but
  I think we are in a decent place at this time.

  NOTE: Takes < 1s for a check and ~6s to ~30s for full download as of Jan-2025"
  [force?]
  (let [ctx (atom {:requests []})
        start-ts (System/currentTimeMillis)
        artifacts (mapcat #(load-maven-central-artifacts-for ctx % force?) maven-groups)
        end-ts (System/currentTimeMillis)
        requests (:requests @ctx)]
    (log/infof "Downloaded %d artifacts from Maven Central in %.02fs via %d requests to maven search REST API (of which %d were retries)"
               (count artifacts)
               (/ (- end-ts start-ts) 1000.0)
               (count requests)
               (- (count requests) (count (distinct requests))))
    artifacts))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(comment
  (fetch-maven-description (atom {:requests []})
                           {:group-id "org.clojure" :artifact-id "clojure" :versions ["1.12.0"]})
  ;; => "Clojure core environment and runtime library."

  (def artifacts (load-maven-central-artifacts false))

  (count artifacts)
  ;; => 80

  (reset! maven-grp-version-counts nil)

  :eoc)

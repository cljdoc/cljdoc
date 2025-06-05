(ns cljdoc.server.search.maven-central
  "Fetch listing of artifacts from maven central."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [cljdoc-shared.pom :as pom]
   [cljdoc.spec :as cljdoc-spec]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [robert.bruce :as rb])
  (:import [java.time Instant]))

(defonce last-fetch-time (atom nil))

;; There are not many clojars libraries on maven central.
;; We'll manualy adjust this list for now:
(def ^:private maven-groups ["org.clojure"
                             "io.github.clojure"
                             "com.turtlequeue"])

(defn- fetch-body [ctx url]
  (let [max-tries 5]
    (try
      (rb/try-try-again
       {:sleep 500
        :decay :double ; max 3 retries means: .5s then 1s, 2s, 4s total 7.5s
        :tries max-tries
        :catch Throwable}
       #(do
          (when (not rb/*first-try*)
            (log/warnf rb/*error*  "Try %d of %d: %s" (dec rb/*try*) max-tries url))
          (swap! ctx update :requests conj url)
          (-> url
              (http/get {:as :stream :throw true})
              :body
              io/input-stream
              io/reader)))
      (catch Exception e
        (throw (ex-info (format "failed to fetch from %s after %d tries" url max-tries)
                        {:cljdoc/type :fault
                         :subject :maven-central}
                        e))))))

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

(defn- add-description [ctx cached-artifacts artifact]
  (let [cached-artifact (some #(when (and (= (:artifact-id artifact) (:artifact-id %))
                                          (= (:group-id artifact) (:group-id %)))
                                 %)
                              cached-artifacts)
        d (if (= (:versions cached-artifact) (:versions artifact))
            (:description cached-artifact)
            (do
              (log/infof "Fetching new description from Maven Central for %s/%s" (:group-id artifact) (:artifact-id artifact))
              (fetch-maven-description ctx artifact)))]
    (if d
      (assoc artifact :description d)
      (dissoc artifact :description))))

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
  [ctx cached-artifacts group-id]
  (let [cached-version-cnt (reduce (fn [acc {:keys [versions]}]
                                     (+ acc (count versions)))
                                   0
                                   cached-artifacts)
        versions-cnt (-> (fetch-json ctx (str "https://search.maven.org/solrsearch/select?q=g:" group-id "&core=gav&rows=0"))
                         :response :numFound)]
    (not= versions-cnt cached-version-cnt)))

(defn- mvn-merge-versions [artifacts]
  (->> artifacts
       ;; NOTE Different artifacts are interleaved but in total newer versions it seems come before older ones of any given artifact
       ;; so we cannot use `partition` but `group-by` (which preserves order) works perfectly
       (group-by (juxt :group-id :artifact-id))
       vals
       (map (fn [versions]
              (-> (first versions)
                  (dissoc :version)
                  (assoc :versions (mapv :version versions)))))))

(defn- cache-dir []
  (fs/file "resources" "maven-central-cache"))

(defn- cached-group-file [group-id]
  (fs/file (cache-dir) (str group-id ".edn")))

(defn- get-cached-artifacts [group-id]
  (let [f (cached-group-file group-id)]
    (when (fs/exists? f)
      (-> f
          slurp
          edn/read-string))))

(defn- update-cached-artifacts! [group-id artifacts]
  (let [f (cached-group-file group-id)]
    (fs/create-dirs (fs/parent f))
    (with-open [out (io/writer f)]
      ;; pprint to support easy inspection and diff
      (pprint/write artifacts :stream out))))

(defn- wipe-cache []
  (fs/delete-tree (cache-dir)))

(defn- refresh-cache-for [ctx group-id {:keys [force-fetch?]}]
  (let [cached-artifacts (get-cached-artifacts group-id)]
    (if (or force-fetch? (let [new? (new-artifacts? ctx cached-artifacts group-id)]
                           (when (not new?)
                             (log/infof "Skipping Maven download for group-id %s, cache is up to date" group-id))
                           new?))
      (do
        (log/infof "Downloading group-id %s from Maven Central to refresh stale cache" group-id)
        (let [artifacts (->> (fetch-maven-docs ctx (str "g:" group-id))
                             (map maven-doc->artifact)
                             (mvn-merge-versions)
                             (mapv #(add-description ctx cached-artifacts %)))]
          (update-cached-artifacts! group-id artifacts)
          artifacts))

      cached-artifacts)))

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
  - we employ artifact group level caching, the number of versions in a group changes we refetch all artifacts in the group
  - we only fetch the description for an artifact if the number of versions has changed for that artifact
  - it is very rare that a group will have a new artifact, so typically there will be 3 requests per hour

  This puts us well under the threshold of 1000 requests in a span of 5 minutes.

  Our cache maven central artifacts cache is tiny so there's no need to store in a database.
  We treat it as a resource and check it in along with code.
  The dev team can periodically commit/push cache updates, but even if they don't, we are much better off than we were request-wise
  before caching was added.

  REPL friendly options:
  - `:wipe-cache?` To force a complete regeneration of the cache, call with `:wipe-cache true`.
  - `:force-fetch?` Artifacts will be returned on first sucessful fetch and then-after only if there are changes,
  use `:force-fetch true` to force a fetch and return artifacts. You don't need to `:force-fetch` if you are
  already specifying `:wipe-cache`.

  NOTE: Takes < 1s for a check and ~6s to ~30s when all groups changed as of Jan-2025"
  ([] (load-maven-central-artifacts {}))
  ([{:keys [wipe-cache? force-fetch?] :as opts}]
   ;; for REPL support
   (when wipe-cache?
     (wipe-cache))
   (let [artifacts-before (mapcat get-cached-artifacts maven-groups)
         ctx (atom {:requests []})
         start-ts (System/currentTimeMillis)
         artifacts-after (mapcat #(refresh-cache-for ctx % opts) maven-groups)
         end-ts (System/currentTimeMillis)
         requests (:requests @ctx)]
     (log/infof "Downloaded %d artifacts from Maven Central in %.02fs via %d requests to maven search REST API (of which %d were retries)"
                (count artifacts-after)
                (/ (- end-ts start-ts) 1000.0)
                (count requests)
                (- (count requests) (count (distinct requests))))
     (when (or force-fetch? (not @last-fetch-time) (not= artifacts-before artifacts-after))
       (reset! last-fetch-time (Instant/now))
       artifacts-after))))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(comment
  (fetch-maven-description (atom {:requests []})
                           {:group-id "org.clojure" :artifact-id "clojure" :versions ["1.12.0"]})
  ;; => "Clojure core environment and runtime library."

  (wipe-cache)

  (reset! last-fetch-time nil)

  (def artifacts (load-maven-central-artifacts))

  (count artifacts)
  ;; => 80

  (def a2 (load-maven-central-artifacts {:force-fetch? true}))

  (def a (get-cached-artifacts "org.clojure"))

  (reduce (fn [acc {:keys [versions]}]
            (+ acc (count versions)))
          0
          a)
  ;; => 2034

  (mapcat #(get-cached-artifacts %) maven-groups)

  :eoc)

(ns cljdoc.server.search.maven-central
  "Fetch listing of artifacts from maven central."
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [cljdoc-shared.pom :as pom]
   [cljdoc.spec :as cljdoc-spec]
   [cljdoc.util.repositories :as mvn-repo]
   [cljdoc.util.sentry :as sentry]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log]
   [robert.bruce :as rb])
  (:import [java.time Instant]))

(defonce last-fetch-time (atom nil))

;; There are not many clojars libraries on maven central.
;; We'll manualy adjust this list for now:
(def ^:private maven-groups ["org.clojure"
                             "org.clojure.typed"
                             "io.github.clojure"
                             "com.turtlequeue"])

(def ^:private maven-central-base-url "https://repo1.maven.org/maven2/")

(defn- fetch [ctx url opts]
  (let [max-tries 3]
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
          (http/get url (merge opts {:as :stream :throw true}))))
      (catch Exception e
        (throw (ex-info (format "failed to fetch from %s after %d tries" url max-tries)
                        {:cljdoc/type :fault
                         :subject :maven-central}
                        e))))))

(defn- parse-group-artifacts [in-stream]
  (->> in-stream
       io/reader
       line-seq
       (keep #(second (re-find #"<a href=\"(.*)/\" title=.*</a>" %)))
       (remove #(= "typed" %)) ;; typed is not an artifact, it is part of org.clojure.typed group
       (into [])))

(defn- parse-artifact-versions [in-stream]
  (->> in-stream
       io/reader
       line-seq
       (keep #(second (re-find #"<version>(.*)</version>" %)))
       reverse
       (into [])))

(defn- fetch-maven-description
  "Fetch the description of a Maven Central artifact (if it has any)"
  [ctx group-id artifact-id version]
  (let [url (str maven-central-base-url
                 (mvn-repo/group-path group-id)
                 "/" artifact-id
                 "/" version
                 "/" artifact-id "-" version ".pom")]
    (->> (fetch ctx url {})
         :body
         slurp
         pom/parse
         :artifact-info
         :description)))

(defn- fetch-group
  "Fetch documents matching the query from Maven Central; requires pagination."
  [ctx {:keys [etag artifacts] :as cached-group} group-id]
  (let [url (str maven-central-base-url (mvn-repo/group-path group-id))
        group-response (fetch ctx url (when etag {:headers {"If-None-Match" etag}}))
        etag (get-in group-response [:headers "etag"])]
    ;; TODO: Wrong: we always need to look at artifacts regardless of difference in group dir
    (if (= 304 (:status group-response))
      cached-group
      {:etag etag
       :artifacts (mapv
                   (fn [artifact-id]
                     (let [{:keys [etag] :as cached-artifact} (some #(= artifact-id (:artifact-id %)) artifacts)
                           url (str maven-central-base-url (mvn-repo/group-path group-id) "/" artifact-id "/maven-metadata.xml")
                           artifact-response (fetch ctx url (when etag {:headers {"If-None-Match" etag}}))
                           etag (get-in artifact-response [:headers "etag"])]
                       (if (= 304 (:status artifact-response))
                         cached-artifact
                         (let [versions (parse-artifact-versions (:body artifact-response))
                               description (fetch-maven-description ctx group-id artifact-id (first versions))]
                           (cond-> {:etag etag
                                    :group-id group-id
                                    :artifact-id artifact-id
                                    :versions versions
                                    :origin :maven-central}
                             description (assoc :description description))))))
                   (parse-group-artifacts (:body group-response)))})))

(defn- cache-dir []
  (fs/file "resources" "maven-central-cache"))

(defn- cached-group-file [group-id]
  (fs/file (cache-dir) (str group-id ".edn")))

(defn- get-cached-group [group-id]
  (let [f (cached-group-file group-id)]
    (when (fs/exists? f)
      (-> f
          slurp
          edn/read-string))))

(defn- update-cached-group! [group-id artifacts]
  (let [f (cached-group-file group-id)]
    (fs/create-dirs (fs/parent f))
    (with-open [out (io/writer f)]
      ;; pprint to support easy inspection and diff
      (pprint/write artifacts :stream out))))

(defn- wipe-cache []
  (fs/delete-tree (cache-dir)))

(defn- refresh-cache-for [ctx group-id _opts]
  (let [cached-group (get-cached-group group-id)
        fetched-group (fetch-group ctx cached-group group-id)]
    (if (= cached-group fetched-group)
      (do
        (log/infof "Skipped Maven download for group-id %s, cache is up to date" group-id)
        cached-group)
      (do
        (log/infof "Downloaded group-id %s from Maven Central to refresh stale cache" group-id)
        (update-cached-group! group-id fetched-group)
        fetched-group))))

(defn- caches->docs [caches]
  (->> caches
       (mapcat :artifacts)
       (mapv #(dissoc % :etag))))

(defn load-maven-central-artifacts
  "Load artifacts from Maven Central - if there are any new ones (or `force?` `true` when testing).
  The Maven Central team has expressed an interest folks minimizing requests when hitting their APIs so we
  make some attempts to do so.

  Maven Central should be OK with our requests, so long as we don't exceed 1000 requests in a span of 5 minutes.

  Previously, we used the  https://search.maven.org/solrsearch REST API, but this stopped working reliably and returned
  over a month stale data to us.
  We've switched to querying the maven repo directly for our handful of groups.

  We make:
  - a fetch per group-id to test for any new artifact versions within the group
  - when there is a change
    - we don't know what has changed in the group-id, a fetch for each artifact within the group
    - when there is a change
      - we fetch the description for the most recent artifact

  All the above maven requests support etags, so that's good.

  At the time of this writing (Jul-2025):
  - we check once each hour
  - we check 3 groups, so this means a minimum of 3 requests
  - we employ artifact group level caching if the group changes check each artifact metadata in the group for changes
  - we only fetch the description for an artifact if the artifact metadata has changed
  - it is very rare that a group will have a new artifact, so typically there will be 3 requests per hour

  - over our 3 groups we track

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

  NOTE: Takes ~10s as of Jul-2025"
  ([] (load-maven-central-artifacts {}))
  ([{:keys [wipe-cache? force-fetch?] :as opts}]
   ;; for REPL support
   (when wipe-cache?
     (wipe-cache))
   (let [groups-before (mapv get-cached-group maven-groups)]
     (try
       (let [ctx (atom {:requests []})
             start-ts (System/currentTimeMillis)
             groups-after (mapv #(refresh-cache-for ctx % opts) maven-groups)
             end-ts (System/currentTimeMillis)
             requests (:requests @ctx)]
         (log/infof "Downloaded %d artifacts from Maven Central in %.02fs via %d requests to maven search REST API (of which %d were retries)"
                    (->> groups-after
                        (mapcat :artifacts)
                        count)
                    (/ (- end-ts start-ts) 1000.0)
                    (count requests)
                    (- (count requests) (count (distinct requests))))
         (when (or force-fetch? (not @last-fetch-time) (not= groups-before groups-after))
           (reset! last-fetch-time (Instant/now))
           (caches->docs groups-after)))
       (catch Exception e
         (log/error e "Failed to download artifacts from Maven Central, returning cached artifacts")
         (sentry/capture {:ex e})
         (when (or force-fetch? (not @last-fetch-time))
           (caches->docs groups-before)))))))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(comment
  (fetch (atom {:requests []}) "https://repo1.maven.org/maven2/org/clojure" {})

  (def groups-before (mapv get-cached-group maven-groups))


  (fetch-maven-description (atom {:requests []})
                           "org.clojure" "clojure" "1.12.0")
  ;; => "Clojure core environment and runtime library."

  (wipe-cache)

  (reset! last-fetch-time nil)

  (def artifacts (load-maven-central-artifacts))

  (count artifacts)
  ;; => 92

  (def a2 (load-maven-central-artifacts {:force-fetch? true}))

  (def a (get-cached-group "org.clojure"))

  (mapcat #(get-cached-group %) maven-groups)

  :eoc)

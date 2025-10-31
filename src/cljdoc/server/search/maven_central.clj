(ns cljdoc.server.search.maven-central
  "Fetch listing of artifacts from maven central."
  (:require
   [babashka.fs :as fs]
   [cljdoc-shared.pom :as pom]
   [cljdoc.http-client :as http]
   [cljdoc.spec :as cljdoc-spec]
   [cljdoc.util.repositories :as mvn-repo]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :as pprint]
   [clojure.spec.alpha :as s]
   [clojure.tools.logging :as log])
  (:import [java.time Instant]))

(defonce last-fetch-time (atom nil))

;; There are not many clojars libraries on maven central.
;; We'll manualy adjust this list for now:
(def ^:private maven-artifacts
  [{:group-id "org.clojure"        :exclude ["typed"]}
   {:group-id "org.clojure.typed"}
   {:group-id "io.github.clojure"}
   {:group-id "com.turtlequeue"}
   {:group-id "com.cognitect"      :exclude ["aws"]}
   {:group-id "com.cognitect.aws"  :include ["api"]}
   {:group-id "com.xtdb"           :include ["xtdb-api"]}])

(def ^:private maven-central-base-url "https://repo1.maven.org/maven2/")

(defn- fetch [ctx url opts]
  (swap! ctx update :requests conj url)
  (http/get url (merge opts {:as :stream :throw true})))

(defn- parse-group-artifact-ids [in-stream]
  (->> in-stream
       io/reader
       line-seq
       (keep #(second (re-find #"<a href=\"(.*)/\" title=.*</a>" %)))
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

(defn- request-opts [{:keys [etag last-modified]}]
  (cond-> {}
    etag (assoc-in [:headers "If-None-Match"] etag)
    last-modified (assoc-in [:headers "If-Modified-Since"] last-modified)))

(defn- fetch-group
  "Fetch artifacts from Maven Central."
  [ctx cached-group group-artifacts]
  (let [group-id (:group-id group-artifacts)
        url (str maven-central-base-url (mvn-repo/group-path group-id))
        group-response (fetch ctx url (request-opts cached-group))]
    (if (= 304 (:status group-response))
      cached-group
      (let [cached-artifacts (:artifacts cached-group)
            include-artifacts (:include group-artifacts)
            exclude-artifacts (:exclude group-artifacts)
            artifact-ids (cond->> (parse-group-artifact-ids (:body group-response))
                           (seq include-artifacts) (filter #(some #{%} include-artifacts))
                           (seq exclude-artifacts) (remove #(some #{%} exclude-artifacts)))]
        {:etag (get-in group-response [:headers "etag"])
         :last-modified (get-in group-response [:headers "last-modified"])
         :artifacts (->> artifact-ids
                         (mapv
                          (fn [artifact-id]
                            (let [cached-artifact (some #(when (= artifact-id (:artifact-id %)) %) cached-artifacts)
                                  url (str maven-central-base-url (mvn-repo/group-path group-id) "/" artifact-id "/maven-metadata.xml")
                                  artifact-response (fetch ctx url (request-opts cached-artifact))]
                              (if (= 304 (:status artifact-response))
                                cached-artifact
                                (let [versions (parse-artifact-versions (:body artifact-response))
                                      description (fetch-maven-description ctx group-id artifact-id (first versions))]
                                  (cond-> {:etag (get-in artifact-response [:headers "etag"])
                                           :last-modified (get-in artifact-response [:headers "last-modified"])
                                           :artifact-id artifact-id
                                           :group-id group-id
                                           :origin :maven-central
                                           :versions versions}
                                    description (assoc :description description)))))))
                         (sort-by :artifact-id)
                         (into []))}))))

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
  (let [dir (cache-dir)]
    (log/info "Deleting cache" (str dir))
    (fs/delete-tree dir)))

(defn- refresh-cache-for [ctx group-artifacts _opts]
  (let [group-id (:group-id group-artifacts)]
    (log/infof "Checking group-id %s" group-artifacts)
    (let [cached-group (get-cached-group group-id)
          fetched-group (fetch-group ctx cached-group group-artifacts)]
      (if (= cached-group fetched-group)
        (do
          (log/infof "Cache was up to date for group-id %s" group-artifacts)
          cached-group)
        (do
          (log/infof "Refreshed stale cache for group-id %s" group-artifacts)
          (update-cached-group! group-id fetched-group)
          fetched-group)))))

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
  - we check 7 groups, so this means a minimum of 7 requests
  - we employ artifact group level caching if the group changes check each artifact metadata in the group for changes
  - we only fetch the description for an artifact if the artifact metadata has changed
  - it is very rare that a group will have a new artifact, so typically there will be 3 requests per hour

  - over our 7 groups we track 108 artifacts
    - worst case is
      - 1 request per group to discover artifacts, if change:
        - 1 request per artifact for metdata, if change:
          - 1 request per artifact for description
      = 7 + 108 + 108 = 223 requests
    - best case is that we detected no changes in groups
      = 7 requests

  Maven Central seems to:
  - Update `last-modified` for dirs when any child has changed, for us this is the group request.
    We'll have to adapt if this is not truly the case.
  - Update `etag` for files, for us, this is the metadata xml request

  Because it has changed in the past we always save both `etag` and `last-modified` in our cache.
  We prefer `etag` if available and fallback to `last-modified`, if available.

  This keeps us under the threshold of 1000 requests in a span of 5 minutes.
  But we are closer to this limit than I'd like.
  For this reason, I've removed retries. If we have any single failure, we fallback to the cache
  and try again in 1 hour.

  Our cache maven central artifacts cache is tiny so there's no need to store in a database.
  We treat it as a resource and check it in along with code.
  The dev team can periodically commit/push cache updates, but even if they don't, we are much better off than we were request-wise
  before caching was added.

  REPL friendly options:
  - `:wipe-cache?` To force a complete regeneration of the cache, call with `:wipe-cache true`.
  - `:force-fetch?` Artifacts will be returned on first sucessful fetch and then-after only if there are changes,
  use `:force-fetch true` to force a return artifacts. You don't need to `:force-fetch` if you are
  already specifying `:wipe-cache`.

  NOTE: Takes ~10s as of Jul-2025"
  ([] (load-maven-central-artifacts {}))
  ([{:keys [wipe-cache? force-fetch?] :as opts}]
   ;; for REPL support
   (when wipe-cache?
     (wipe-cache))
   (let [artifacts-before (->> maven-artifacts
                               (mapv :group-id)
                               (mapv get-cached-group))]
     (try
       (let [ctx (atom {:requests []})
             start-ts (System/currentTimeMillis)
             artifacts-after (mapv #(refresh-cache-for ctx % opts) maven-artifacts)
             end-ts (System/currentTimeMillis)
             requests (:requests @ctx)]
         (log/infof "Downloaded %d artifacts from Maven Central in %.02fs via %d requests to maven repository"
                    (->> artifacts-after
                         (mapcat :artifacts)
                         count)
                    (/ (- end-ts start-ts) 1000.0)
                    (count requests))
         (when (or force-fetch? (not @last-fetch-time) (not= artifacts-before artifacts-after))
           (reset! last-fetch-time (Instant/now))
           (caches->docs artifacts-after)))
       (catch Exception e
         (log/error e "Failed to download artifacts from Maven Central, returning cached artifacts")
         (when (or force-fetch? (not @last-fetch-time))
           (caches->docs artifacts-before)))))))

(s/fdef load-maven-central-artifacts
  :ret (s/nilable (s/every ::cljdoc-spec/artifact)))

(comment
  (def res (fetch (atom {:requests []})
                  "https://repo1.maven.org/maven2/io/github/clojure/tools.build/maven-metadata.xml" {}))

  (def groups-before (->> maven-artifacts
                          (mapv :group-id)
                          (mapv get-cached-group)))

  (fetch-maven-description (atom {:requests []})
                           "org.clojure" "clojure" "1.12.3")
  ;; => "Clojure core environment and runtime library."

  (wipe-cache)

  (reset! last-fetch-time nil)

  (def artifacts (load-maven-central-artifacts {:force-fetch? true}))

  (count artifacts)
  ;; => 108

  (def a2 (load-maven-central-artifacts {:force-fetch? true}))

  (def a (get-cached-group "org.clojure"))

  :eoc)

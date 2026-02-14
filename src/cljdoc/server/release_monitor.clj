(ns cljdoc.server.release-monitor
  "Monitor for new library releases between hourly bulk checks done by cljdoc.server.search.api.

  We only monitor clojars and not maven central.

  Maven Central
  - does not hold many clojure libs (notable exception is org.clojure libs)
  - their team asks that we only make necessary calls to their APIs to reduce overall traffic
  - existing hourly bulk update check is sufficient
  - manual invokation of doc building is still possible for the impatient"
  (:require [cljdoc-shared.proj :as proj]
            [cljdoc.http-client :as http]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.search.api :as sc]
            [cljdoc.util.repositories :as repositories]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [next.jdbc.sql :as sql]
            [tea-time.core :as tt])
  (:import (cljdoc.server.search.api ISearcher)
           (java.net URI)
           (java.time Duration Instant)
           (java.util Date)))

(set! *warn-on-reflection* true)

;;
;; Clojars
;;

(defn- clojars-artifact-info [{:keys [group-id artifact-id]}]
  (let [result (->> (http/get (format "http://clojars.org/api/artifacts/%s/%s" group-id artifact-id)
                              {:headers {:accept "application/edn"}})
                    :body
                    edn/read-string)]
    {:versions (->> result :recent_versions (mapv :version))}))

(defn- clojars-releases-since [from-inst]
  (let [resp (http/get "https://clojars.org/api/release-feed"
                       {:query-params {"from" (str from-inst)}
                        :headers {:accept "application/edn"}})
        results (-> resp :body edn/read-string :releases)]
    (->> results
         (map (fn [r]
                ;; clojars encodes released_at as, e.g.: #inst "2024-12-28T21:27T21:27:00.686000000-00:00"
                {:created-ts  (.toInstant ^Date (:released_at r))
                 :group-id    (:group_id r)
                 :artifact-id (:artifact_id r)
                 :version     (:version r)
                 :description (:description r)})))))

;;
;; Local sql db
;;

(defn- last-release-ts ^Instant [db-spec]
  (some-> (sql/query db-spec ["SELECT * FROM releases ORDER BY datetime(created_ts) DESC LIMIT 1"]
                     jdbc/unqualified-snake-kebab-opts)
          first
          :created-ts
          Instant/parse))

(defn- oldest-not-built
  "Return the oldest not yet built release, excluding releases younger than 10 minutes.

  The 10min delay has been put into place to avoid building projects before people had a chance
  to push the respective commits or tags to their git repositories."
  [db-spec]
  (first (sql/query db-spec ["SELECT * FROM releases WHERE build_id IS NULL AND datetime(created_ts) < datetime('now', '-10 minutes') ORDER BY retry_count ASC, datetime(created_ts) ASC LIMIT 1"]
                    jdbc/unqualified-snake-kebab-opts)))

(defn- update-build-id!
  [db-spec release-id build-id]
  (sql/update! db-spec
               "releases"
               {:build-id build-id}
               ["id = ?" release-id]
               jdbc/unqualified-snake-kebab-opts))

(defn- is-known-release? [connectable {:keys [group-id artifact-id version]}]
  (-> (jdbc/execute-one! connectable
                         [(str "SELECT EXISTS ("
                               " SELECT 1 "
                               " FROM releases "
                               " WHERE group_id = ? "
                               " AND artifact_id = ? "
                               " AND version = ? "
                               ") AS duplicate")
                          group-id artifact-id version])
      :duplicate
      zero?
      not))

(defn- queue-new-releases
  "Queues `releases` skipping any already known releases, returns releases that were queued."
  [db-spec releases]
  (when (seq releases)
    (log/infof "Queuing %s new releases" (count releases))
    (reduce (fn [acc {:keys [group-id artifact-id version] :as release}]
              (jdbc/with-transaction [tx db-spec]
                (if (is-known-release? tx release)
                  (log/warnf "Skipping %s/%s %s, is aleady known to cljdoc."
                             group-id artifact-id version)
                  (do
                    (log/infof "Queueing %s/%s %s for build."
                               group-id artifact-id version)
                    (sql/insert! tx :releases
                                 (select-keys release [:group-id :artifact-id :version :created-ts])
                                 jdbc/unqualified-snake-kebab-opts)
                    (conj acc release)))))
            []
            releases)))

(defn- inc-retry-count! [db-spec {:keys [id retry-count] :as _release}]
  (sql/update! db-spec
               :releases
               {:retry-count (inc retry-count)}
               {:id id}
               jdbc/unqualified-snake-kebab-opts))

;;
;; Logic and jobs
;;

(defn- trigger-build
  [{:keys [group-id artifact-id version] :as _release} server-port]
  ;; I'm really not liking that this makes it all very tied to the HTTP server... - martin
  (let [resp (http/post (str "http://localhost:" server-port "/api/request-build2")
                        {:follow-redirects :never
                         :form-params {:project (str group-id "/" artifact-id)
                                       :version version}
                         :headers {:content-type "application/x-www-form-urlencoded"}})
        ^URI location (:uri resp)
        build-id (some->> location
                          .getPath
                          (re-find #"/builds/(\d+)")
                          (second))]
    (assert build-id "Could not extract build-id from response")
    build-id))

(defn- update-artifact-index [searcher releases]
  (when (seq releases)
    (log/infof "Writing %d artifacts to search index" (count releases))
    (->> releases
         (mapv (fn [{:keys [group-id artifact-id] :as r}]
                 ;; fetch complete :versions to allow for replace of indexed artifact instead of
                 ;; some sort of update
                 (log/infof "Fetching info for %s/%s" group-id artifact-id)
                 (let [{:keys [versions]} (clojars-artifact-info r)]
                   (assoc r
                          :origin :clojars
                          :versions versions))))
         (sc/index-artifacts searcher))))

(defn- exclude-new-release?
  [{:keys [group-id artifact-id version] :as _build}]
  (or (= "cljsjs" group-id)
      (string/ends-with? version "-SNAPSHOT")
      (and (= "org.akvo.flow" group-id)
           (= "akvo-flow" artifact-id))
      (= "lein-template" artifact-id)
      (string/includes? group-id "gradle-clojure")
      (string/includes? group-id "gradle-clj")))

(defn- release-fetch-job-fn [{:keys [db-spec ^ISearcher searcher]}]
  (let [ts-last-release (last-release-ts db-spec)
        releases (if ts-last-release
                   (clojars-releases-since ts-last-release)
                   (clojars-releases-since (.minus (Instant/now) (Duration/ofHours 24))))
        releases (remove exclude-new-release? releases)]
    (when (seq releases)
      (let [queued (queue-new-releases db-spec releases)]
        (update-artifact-index searcher queued)))))

(defn- pre-check-failed! [db-spec build-tracker {:keys [:id :group-id :artifact-id version] :as _release-to-build} error]
  (let [build-id (build-log/analysis-requested! build-tracker group-id artifact-id version)]
    (log/warnf "pre-check-failed for %s/%s %s, failing build with error %s"  group-id artifact-id version error)
    (build-log/failed! build-tracker build-id error)
    (update-build-id! db-spec id build-id)))

(defn- resolve-clojars-artifact [maven-repositories {:keys [group-id artifact-id version] :as _release}]
  (let [clojars-repo (->> maven-repositories
                          (filter #(= "clojars" (:id %)))
                          first
                          :url)]
    (try
      (repositories/resolve-artifact clojars-repo
                                     (proj/clojars-id {:group-id group-id :artifact-id artifact-id})
                                     version)
      (catch Throwable ex
        {:exception ex :status -1}))))

(defn- build-queuer-job-fn [{:keys [db-spec maven-repositories build-tracker server-port dry-run? max-retries]}]
  (when-let [{:keys [id group-id artifact-id version retry-count] :as release-to-build} (oldest-not-built db-spec)]
    (if dry-run?
      (log/infof "Dry-run mode: not triggering build for %s/%s %s"
                 group-id artifact-id version)
      (let [{:keys [exception status] :as _resolve-result} (resolve-clojars-artifact maven-repositories release-to-build)]
        (case (long status)
          200 (do (log/infof "Triggering build for %s/%s %s" group-id artifact-id version)
                  (update-build-id! db-spec id (trigger-build release-to-build server-port)))
          404 (pre-check-failed! db-spec build-tracker release-to-build "listed-artifact-not-found")
          ;; else
          (let [msg (format "failed to resolve %s/%s %s, resolve status %d, retry count %d"
                            group-id artifact-id version status retry-count)]
            (if exception
              (log/warn exception msg)
              (log/warn msg))
            (if (>= retry-count max-retries)
              (pre-check-failed! db-spec build-tracker release-to-build "listed-artifact-resolve-failed")
              (inc-retry-count! db-spec release-to-build))))))))

(defmethod ig/init-key :cljdoc/release-monitor [_ opts]
  (log/info "Starting ReleaseMonitor" (if (:dry-run? opts) "(dry-run mode)" ""))
  {:release-fetcher (tt/every! 60
                               #(release-fetch-job-fn
                                 (select-keys opts [:db-spec :searcher])))
   :build-queuer    (tt/every! (* 10 60) 10
                               #(build-queuer-job-fn
                                 (select-keys opts [:db-spec :maven-repositories :build-tracker :server-port :dry-run? :max-retries])))})

(defmethod ig/halt-key! :cljdoc/release-monitor [_ release-monitor]
  (log/info "Stopping ReleaseMonitor")
  (tt/cancel! (:release-fetcher release-monitor))
  (tt/cancel! (:build-queuer release-monitor))
  (tt/stop!))

(comment
  (def cfg (cljdoc.config/config))

  (def db-spec (cljdoc.config/db cfg))
  (oldest-not-built db-spec)
  ;; => nil

  (build-queuer-job-fn {:db-spec db-spec :build-tracker identity :dry-run? false :max-retries 1})

  (def ts (last-release-ts db-spec))
  ts
  ;; => #object[java.time.Instant 0x1db22fea "2025-10-09T16:01:33.830Z"]

  (clojars-releases-since ts)

  (def rm
    (ig/init-key :cljdoc/release-monitor db-spec))

  (ig/halt-key! :cljdoc/release-monitor rm)

  (doseq [r (clojars-releases-since (.minus (Instant/now) (Duration/ofDays 2)))]
    (insert db-spec r))

  (last (sql/query db-spec ["SELECT * FROM releases"]
                   jdbc/unqualified-snake-kebab-opts))

  (clojure.pprint/pprint
   (->> (clojars-releases-since (last-release-ts db-spec))
        (map #(select-keys % [:created-ts]))))

  (oldest-not-built db-spec)

  (clojars-releases-since "2024-11-05T21:53:58.628Z")
  (def foo (clojars-releases-since "2024-11-05T21:54:01.606Z"))
  (count foo)

  (= foo (sort-by :created-ts (shuffle foo)))
  ;; => true

  (queue-new-releases db-spec foo)

  (Instant/ofEpochSecond (parse-long "1515274516"))
  ;; => #object[java.time.Instant 0x4c0c3eed "2018-01-06T21:35:16Z"]

  (first foor)
  ;; => {"jar_name" "confick",
  ;;     "group_name" "de.dixieflatline",
  ;;     "version" "0.1.4",
  ;;     "description" "Simple, stupid configuration management.",
  ;;     "created" "1730927007884"}

  (Instant/ofEpochMilli (parse-long "1730927007884"))
  ;; => #object[java.time.Instant 0x283dc39f "2024-11-06T21:03:27.884Z"]

  (time (clojars-artifact-info {:group-id "rewrite-clj" :artifact-id "rewrite-clj"}))

  :eoc)

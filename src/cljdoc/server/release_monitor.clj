(ns cljdoc.server.release-monitor
  (:require [cheshire.core :as json]
            [clj-http.lite.client :as http]
            [cljdoc-shared.proj :as proj]
            [cljdoc.config :as config]
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
           (java.time Duration Instant)))

(set! *warn-on-reflection* true)

; TODO. Maven Central

;;
;; Clojars
;;

(defn- clojars-artifact-info [{:keys [group-id artifact-id]}]
  (let [result (->> (http/get (format "http://clojars.org/api/artifacts/%s/%s" group-id artifact-id)
                              {:accept :edn})
                    :body
                    edn/read-string)]
    {:description (:description result)
     :versions (->> result :recent_versions (mapv :version))}))

(defn- clojars-releases-since [from-inst]
  (let [req (http/get "https://clojars.org/search"
                      {:query-params {"q" (format "at:[%s TO %s]" (str from-inst) (str (Instant/now)))
                                      "format" "json"
                                      "page" 1}})
        results (-> req :body json/parse-string (get "results"))]
    (->> results
         (sort-by #(get % "created"))
         (map (fn [r]
                {:created-ts  (Instant/ofEpochMilli (parse-long (get r "created")))
                 :group-id    (get r "group_name")
                 :artifact-id (get r "jar_name")
                 :version     (get r "version")})))))

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
  "Queues `releases` skipping any already queued, returns releases that were queued."
  [db-spec releases]
  (let [releases (sort-by :created-ts releases)
        ;; quietly skip last release if already known, we don't have the facility
        ;; to query clojars on exclusive ranges so we get overlaps
        releases (if (is-known-release? db-spec (last releases))
                   (butlast releases)
                   releases)]
    (log/infof "Queuing %s new releases" (count releases))

    (reduce (fn [acc {:keys [group-id artifact-id version] :as release}]
              (jdbc/with-transaction [tx db-spec]
                (if (is-known-release? tx release)
                  (log/warnf "Skipping %s:%s:%s, is aleady known to cljdoc."
                             group-id artifact-id version)
                  (do
                    (log/infof "Queueing %s:%s:%s for build."
                               group-id artifact-id version)
                    (sql/insert! tx :releases
                                 release
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
  [{:keys [id group-id artifact-id version retry-count] :as _release}]
  ;; I'm really not liking that this makes it all very tied to the HTTP server... - martin
  (let [req (http/post (str "http://localhost:" (get-in (config/config) [:cljdoc/server :port]) "/api/request-build2")
                       {:follow-redirects false
                        :form-params {:project (str group-id "/" artifact-id)
                                      :version version}
                        :content-type "application/x-www-form-urlencoded"})
        build-id (some->> (get-in req [:headers "location"])
                          (re-find #"/builds/(\d+)")
                          (second))]
    (assert build-id "Could not extract build-id from response")
    build-id))

(defn- update-artifact-index [searcher releases]
  (run! #(let [a {:artifact-id (:artifact-id %)
                  :group-id (:group-id %)
                  :origin :clojars}]
           (sc/index-artifact
            searcher
            (merge a (clojars-artifact-info a))))
        releases))

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

(defn- resolve-clojars-artifact [{:keys [group-id artifact-id version] :as _release}]
  (let [clojars-repo (->> (config/maven-repositories)
                          (filter #(= "clojars" (:id %)))
                          first
                          :url)]
    (try
      (repositories/resolve-artifact clojars-repo
                                     (proj/clojars-id {:group-id group-id :artifact-id artifact-id})
                                     version)
      (catch Throwable ex
        {:exception ex :status -1}))))

(defn- build-queuer-job-fn [{:keys [db-spec build-tracker dry-run? max-retries]}]
  (when-let [{:keys [id group-id artifact-id version retry-count] :as release-to-build} (oldest-not-built db-spec)]
    (if dry-run?
      (log/infof "Dry-run mode: not triggering build for %s/%s %s"
                 group-id artifact-id version)
      (let [{:keys [exception status] :as _resolve-result} (resolve-clojars-artifact release-to-build)]
        (case (long status)
          200 (do (log/infof "Queuing build for %s" release-to-build)
                  (update-build-id! db-spec id (trigger-build release-to-build)))
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
                                 (select-keys opts [:db-spec :build-tracker :dry-run? :max-retries])))})

(defmethod ig/halt-key! :cljdoc/release-monitor [_ release-monitor]
  (log/info "Stopping ReleaseMonitor")
  (tt/cancel! (:release-fetcher release-monitor))
  (tt/cancel! (:build-queuer release-monitor))
  (tt/stop!))

(comment
  (def cfg (cljdoc.config/config))


  (def db-spec (cljdoc.config/db cfg))

  (build-queuer-job-fn db-spec true)

  (def ts (last-release-ts db-spec))
  ts
  ;; => #object[java.time.Instant 0xf4d333 "2022-12-17T13:09:15.977Z"]

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

  (sort-by :created-ts (shuffle foo))

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

  :eoc)

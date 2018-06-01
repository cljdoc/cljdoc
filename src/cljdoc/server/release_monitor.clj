(ns cljdoc.server.release-monitor
  (:require [cljdoc.util.clojars :as clojars])
  (:require [integrant.core :as ig]
            [aleph.http :as http]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [tea-time.core :as tt])
  (:import (java.time Instant Duration)))

(defn- last-release-ts [db-spec]
  (some-> (sql/query db-spec ["SELECT * FROM releases ORDER BY datetime(created_ts) DESC LIMIT 1"])
          first
          :created_ts
          Instant/parse))

(defn- oldest-not-built [db-spec]
  (first (sql/query db-spec ["SELECT * FROM releases WHERE build_id IS NULL ORDER BY datetime(created_ts) LIMIT 1"])))

(defn- update-build-id
  [db-spec release-id build-id]
  (sql/update! db-spec
               "releases"
               {:build_id build-id}
               ["id = ?" release-id]))

(defn- trigger-build
  [release]
  {:pre [(:id release) (:group_id release) (:artifact_id release) (:version release)]}
  ;; I'm really not liking that this makes it all very tied to the HTTP server... - martin
  (let [req @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/request-build2")
                        {:form-params {:project (str (:group_id release) "/" (:artifact_id release))
                                       :version (:version release)}
                         :content-type "application/x-www-form-urlencoded"})
        build-id (->> (get-in req [:headers "location"])
                      (re-find #"/builds/(\d+)")
                      (second))]
    (assert build-id)
    build-id))

(defn release-fetch-job-fn [db-spec]
  (let [ts (or (some-> (last-release-ts db-spec)
                       (.plus (Duration/ofSeconds 1)))
               (.minus (Instant/now) (Duration/ofHours 24)))
        cljsjs?  #(= "cljsjs" (:group_id %))
        releases (->> (clojars/releases-since ts)
                      (remove cljsjs?))]
    (when (seq releases)
      (log/infof "Storing %s new releases in releases table" (count releases))
      (sql/insert-multi! db-spec "releases" releases))))

(defn build-queuer-job-fn [db-spec dry-run?]
  (when-let [to-build (oldest-not-built db-spec)]
    (if dry-run?
      (log/infof "Dry-run mode: not triggering build for %s/%s %s"
                 (:group_id to-build) (:artifact_id to-build) (:version to-build))
      (let [build-id (trigger-build to-build)]
        (update-build-id db-spec (:id to-build) build-id)))))

(defmethod ig/init-key :cljdoc/release-monitor [_ {:keys [db-spec dry-run?]}]
  (log/info "Starting ReleaseMonitor" (when dry-run? "(dry-run mode)"))
  (tt/start!)
  {:release-fetcher (tt/every! 60 #(release-fetch-job-fn db-spec))
   :build-queuer    (tt/every!
                     ;; Starting conservatively, building one project per 30min
                     ;; But really instead of running this once an hour we should
                     ;; rate limit and run it more often so builds are becoming available
                     ;; as they are released
                     (* 30 60 60)
                     #(build-queuer-job-fn db-spec dry-run?))})

(defmethod ig/halt-key! :cljdoc/release-monitor [_ release-monitor]
  (log/info "Stopping ReleaseMonitor")
  (tt/cancel! (:release-fetcher release-monitor))
  (tt/cancel! (:build-queuer release-monitor)))

(comment
  (def db-spec (cljdoc.config/build-log-db))

  (build-queuer-job-fn db-spec true)

  (def rm
    (ig/init-key :cljdoc/release-monitor db-spec))

  (ig/halt-key! :cljdoc/release-monitor rm)

  (doseq [r (cljdoc.util.clojars/releases-since (.minus (Instant/now) (Duration/ofDays 2)))]
    (insert db-spec r))

  (last (sql/query db-spec ["SELECT * FROM releases"]))

  (trigger-build db-spec (first (sql/query db-spec ["SELECT * FROM releases"])))

  (clojure.pprint/pprint
   (->> (clojars/releases-since (last-release-ts db-spec))
        (map #(select-keys % [:created_ts]))))

  (oldest-not-built db-spec)

  

  )

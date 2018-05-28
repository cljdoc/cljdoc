(ns cljdoc.server.build-log
  (:require [integrant.core :as ig]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime])
  (:import (java.time Instant)))

(defn- now []
  (str (Instant/now)))

(defprotocol IBuildTracker
  (analysis-requested!
    ;; "Track a request for analysis and return the build's ID."
    [_ group-id artifact-id version])
  (analysis-kicked-off! [_ build-id analysis-job-uri])
  (analysis-received! [_ build-id cljdoc-edn-uri])
  (completed! [_ build-id scm-url commit])
  (get-build [_ build-id])
  (recent-builds [_ limit]))

(defrecord SQLBuildTracker [db-spec]
  IBuildTracker
  (analysis-requested! [_ group-id artifact-id version]
    (->> (sql/insert! db-spec
                      "builds"
                      {:group_id group-id
                       :artifact_id artifact-id
                       :version version
                       :analysis_requested_ts (now)})
         (first)
         ((keyword "last_insert_rowid()"))))
  (analysis-kicked-off! [_ build-id analysis-job-uri]
    (sql/update! db-spec
                 "builds"
                 {:analysis_job_uri analysis-job-uri
                  :analysis_triggered_ts (now)}
                 ["id = ?" build-id]))
  (analysis-received! [_ build-id cljdoc-edn-uri]
    (sql/update! db-spec
                 "builds"
                 {:analysis_result_uri cljdoc-edn-uri
                  :analysis_received_ts (now)}
                 ["id = ?" build-id]))
  (completed! [_ build-id scm-url commit]
    (sql/update! db-spec
                 "builds"
                 {:scm_url scm-url
                  :commit_sha commit
                  :import_completed_ts (now)}
                 ["id = ?" build-id]))
  (get-build [_ build-id]
    (first (sql/query db-spec ["SELECT * FROM builds WHERE id = ?" build-id])))
  (recent-builds [_ limit]
    (sql/query db-spec ["SELECT * FROM builds ORDER BY id DESC LIMIT ?" limit])))

(defmethod ig/init-key :cljdoc/build-tracker [_ db-spec]
  (log/info "Starting BuildTracker")
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources "build_log_migrations")
                       {:reporter (fn [store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))})
  (->SQLBuildTracker db-spec))

(comment
  (ragtime.repl/rollback config)
  (ragtime.repl/migrate config)

  (def bt (->SQLBuildTracker (cljdoc.config/build-log-db)))

  (recent-builds  1)

  (clojure.pprint/pprint
   (get-build db 1))

  (ragtime/migrate-all (jdbc/sql-database db)
                       {}
                       (jdbc/load-resources "migrations")
                       {:reporter (fn [store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))})

  (sql/update! db "builds" {:analysis_job_uri "hello world"} ["id = ?" 9])

  (sql/query db ["UPDATE builds SET analysis_job_uri = ? WHERE id = ?" "hello world" 9])

  (analysis-requested! bt "bidi" "bidi" "2.1.3")
 
  (track-analysis-kick-off! db 2 "xxx")

  )


;; insert into builds (group_id, artifact_id, version, analysis_triggered_ts) values ('xxx', 'aaa', '1.0.0', datetime('now'));

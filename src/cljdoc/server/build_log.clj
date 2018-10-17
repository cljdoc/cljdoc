(ns cljdoc.server.build-log
  (:require [integrant.core :as ig]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [cljdoc.util.telegram :as telegram])
  (:import (java.time Instant Duration)))

(defn- now []
  (str (Instant/now)))

(defprotocol IBuildTracker
  (analysis-requested!
    ;; "Track a request for analysis and return the build's ID."
    [_ group-id artifact-id version])
  (analysis-kicked-off! [_ build-id analysis-job-uri analyzer-version])
  (analysis-received! [_ build-id cljdoc-edn-uri])
  (failed! [_ build-id error])
  (api-imported! [_ build-id])
  (completed! [_ build-id git-result])
  (get-build [_ build-id])
  (recent-builds [_ limit])
  (running-build [_ group-id artifact-id version]))

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
  (analysis-kicked-off! [_ build-id analysis-job-uri analyzer-version]
    (sql/update! db-spec
                 "builds"
                 {:analysis_job_uri analysis-job-uri
                  :analysis_triggered_ts (now)
                  :analyzer_version analyzer-version}
                 ["id = ?" build-id]))
  (analysis-received! [_ build-id cljdoc-edn-uri]
    (sql/update! db-spec
                 "builds"
                 {:analysis_result_uri cljdoc-edn-uri
                  :analysis_received_ts (now)}
                 ["id = ?" build-id]))
  (failed! [this build-id error]
    (telegram/build-failed (assoc (get-build this build-id) :error error))
    (sql/update! db-spec "builds" {:error error} ["id = ?" build-id]))
  (api-imported! [this build-id]
    (sql/update! db-spec "builds" {:api_imported_ts (now)} ["id = ?" build-id]))
  (completed! [this build-id {:keys [scm-url error commit] :as git-result}]
    (telegram/import-completed (get-build this build-id) (if git-result error "repo-not-provided"))
    (sql/update! db-spec
                 "builds"
                 {:scm_url scm-url
                  :commit_sha commit
                  :git_imported_ts (when (and git-result (nil? error)) (now))
                  :git_problem (if git-result error "repo-not-provided")
                  :import_completed_ts (now)}
                 ["id = ?" build-id]))
  (get-build [_ build-id]
    (first (sql/query db-spec ["SELECT * FROM builds WHERE id = ?" build-id])))
  (recent-builds [_ limit]
    (sql/query db-spec ["SELECT * FROM builds ORDER BY id DESC LIMIT ?" limit]))
  (running-build [_ group-id artifact-id version]
    (first
     (sql/query db-spec [(str "select * from builds where error is null "
                              "and import_completed_ts is null "
                              "and group_id = ? and artifact_id = ? and version = ? "
                              ;; HACK; this datetime scoping shouldn't be required but
                              ;; in practice it happens that some webhooks don't reach
                              ;; the cljdoc api and builds end up in some sort of limbo
                              ;; where neither an error nor completion has occurred
                              "and datetime(analysis_requested_ts) > datetime(?) "
                              "order by id desc "
                              "limit 1")
                         group-id artifact-id version
                         (str (.minus (Instant/now) (Duration/ofMinutes 10)))]))))

(defmethod ig/init-key :cljdoc/build-tracker [_ db-spec]
  (log/info "Starting BuildTracker")
  (->SQLBuildTracker db-spec))

(comment
  (require 'ragtime.repl 'ragtime.jdbc)
  (def config {:datastore  (ragtime.jdbc/sql-database (cljdoc.config/db))
               :migrations (ragtime.jdbc/load-resources "migrations")})

  (ragtime.repl/rollback config)

  (ragtime.repl/migrate config)

  (def bt (->SQLBuildTracker (cljdoc.config/db)))

  (running-build bt "amazonica" "amazonica" "0.3.132")

  (doseq [v [:success :success-no-git :git-problem :fail :kicked-off :analysis-received :api-imported]]
    (let [id (analysis-requested! bt (name v) (name v) "0.8.0")]
      (analysis-kicked-off! bt id "fake-url")
      (when-not (= :kicked-off v)
        (analysis-received! bt id "fake-url"))
      (case v
        :api-imported (api-imported! bt id)
        :success (do (api-imported! bt id)
                     (completed! bt id {:scm-url "http://github.com/this/is-a-test" :commit "TESTING"}))
        :success-no-git (do (api-imported! bt id)
                            (completed! bt id nil))
        :git-problem (do (api-imported! bt id)
                         (completed! bt id {:error "unknown-revision"}))
        :fail (do (failed! bt id "exception"))
        nil)))

  (sql/update! db "builds" {:analysis_job_uri "hello world"} ["id = ?" 9])

  (sql/query db ["UPDATE builds SET analysis_job_uri = ? WHERE id = ?" "hello world" 9])

  (analysis-requested! bt "bidi" "bidi" "2.1.3")

  (track-analysis-kick-off! db 2 "xxx")

  )


;; insert into builds (group_id, artifact_id, version, analysis_triggered_ts) values ('xxx', 'aaa', '1.0.0', datetime('now'));

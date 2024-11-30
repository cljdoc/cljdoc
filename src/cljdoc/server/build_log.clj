(ns cljdoc.server.build-log
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [taoensso.nippy :as nippy])
  (:import (java.time Duration Instant LocalDate ZoneOffset)
           (java.time.format DateTimeFormatter)))

(set! *warn-on-reflection* true)

(defn- now []
  (str (Instant/now)))

(defn- occlude-sensitive [m]
  ;; imperfect but good enough.
  ;; we've switched to babashka http-client which includes the request in its throw
  ;; which can include sensitive tokens
  ;; TODO: temporary, we soon will not be saving potentially sensitive info to db
  (walk/postwalk (fn form-visitor [x]
                   (println "visiting" x)
                   (if-not (map? x)
                     x
                     (reduce-kv
                      (fn [m k v]
                        (if (str/includes? (-> k str str/lower-case)
                                           "token")
                          (assoc m k :OCCLUDED)
                          (assoc m k v)))
                      {}
                      x)))
                 m))

(defprotocol IBuildTracker
  (analysis-requested!
    ;; "Track a request for analysis and return the build's ID."
    [_ group-id artifact-id version])
  (analysis-kicked-off! [_ build-id analysis-job-uri analyzer-version])
  (analysis-received! [_ build-id cljdoc-analysis-edn-uri])
  (failed! [_ build-id error] [_ build-id error e])
  (api-imported! [_ build-id namespaces-count])
  (git-completed! [_ build-id git-result])
  (completed! [_ build-id])
  (get-build [_ build-id])
  (get-builds [_ end-date-str days])
  (running-build [_ group-id artifact-id version])
  (last-build [_ group-id artifact-id version]))

(defrecord SQLBuildTracker [db-spec]
  IBuildTracker
  (analysis-requested! [_ group-id artifact-id version]
    (->> (jdbc/execute-one! db-spec

                            ;; SQLite uses RETURNING syntax to return the generated id
                            ["INSERT INTO builds (group_id, artifact_id, version, analysis_requested_ts) VALUES (?,?,?,?) RETURNING id"
                             group-id artifact-id version (now)]
                            {:builder-fn rs/as-unqualified-maps})
         :id))
  (analysis-kicked-off! [_ build-id analysis-job-uri analyzer-version]
    (sql/update! db-spec
                 :builds
                 {:analysis_job_uri analysis-job-uri
                  :analysis_triggered_ts (now)
                  :analyzer_version analyzer-version}
                 {:id build-id}))
  (analysis-received! [_ build-id cljdoc-analysis-edn-uri]
    (sql/update! db-spec
                 :builds
                 {:analysis_result_uri cljdoc-analysis-edn-uri
                  :analysis_received_ts (now)}
                 {:id build-id}))
  (failed! [this build-id error]
    (failed! this build-id error nil))
  (failed! [_ build-id error e]
    (sql/update! db-spec :builds (cond-> {:error error}
                                   e (assoc :error_info_map
                                            (-> e
                                                Throwable->map
                                                occlude-sensitive
                                                nippy/freeze)))
                 {:id build-id}))
  (api-imported! [_this build-id namespaces-count]
    (sql/update! db-spec
                 :builds
                 {:api_imported_ts (now)
                  :namespaces_count namespaces-count}
                 {:id build-id}))
  (git-completed! [_this build-id {:keys [scm-url error commit] :as git-result}]
    (sql/update! db-spec
                 :builds
                 {:scm_url scm-url
                  :commit_sha commit
                  :git_imported_ts (when (and git-result (nil? error)) (now))
                  :git_problem (if git-result error "repo-not-provided")}
                 {:id build-id}))
  (completed! [_this build-id]
    (sql/update! db-spec :builds {:import_completed_ts (now)} {:id build-id}))
  (get-build [_ build-id]
    (-> (sql/query db-spec ["SELECT * FROM builds WHERE id = ?" build-id]
                   {:builder-fn rs/as-unqualified-maps})
        first))
  (get-builds [_ end-date-str days]
    (let [formatter (DateTimeFormatter/ofPattern "yyyy-MM-dd")
          ^LocalDate end-date (or (and end-date-str (LocalDate/parse end-date-str formatter))
                                  (LocalDate/now ZoneOffset/UTC))
          day-strs (mapv #(-> end-date
                              (.minusDays %)
                              (.format formatter))
                         (range days))
          start-date-str (last day-strs)
          builds-by-date (->> (sql/query db-spec [(str "SELECT * "
                                                       "FROM builds "
                                                       "WHERE analysis_requested_ts "
                                                       "BETWEEN ? AND ? "
                                                       "ORDER BY analysis_requested_ts DESC")
                                                  start-date-str
                                                  (.format formatter (.plusDays end-date 1))]
                                         {:builder-fn rs/as-unqualified-maps})
                              (group-by #(subs (:analysis_requested_ts %) 0 10)))]
      (mapv (fn [day-str]
              {:date day-str
               :builds (get builds-by-date day-str [])})
            day-strs)))
  (running-build [_ group-id artifact-id version]
    (-> (sql/query db-spec [(str "select * from builds where error is null "
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
                            (str (.minus (Instant/now) (Duration/ofMinutes 10)))]
                   {:builder-fn rs/as-unqualified-maps})
        first))
  (last-build [_ group-id artifact-id version]
    (-> (sql/query db-spec [(str "select * from builds where "
                                 "(group_id = ? "
                                 "and artifact_id = ? "
                                 "and version = ?) "
                                 "and (import_completed_ts is not null "
                                 "or error is not null) "
                                 "order by id desc "
                                 "limit 1")
                            group-id artifact-id version]
                   {:builder-fn rs/as-unqualified-maps})
        first)))

(defn api-import-successful? [build]
  (and (:api_imported_ts build)
       (nil? (:error build))))

(defn git-import-successful? [build]
  (and (:scm_url build)
       (:git_imported_ts build)
       (nil? (:git_problem build))))

(comment
  (require '[cljdoc.config :as cfg])
  (def db-spec (-> (cfg/config) (cfg/db)))

  (def bt (->SQLBuildTracker db-spec))

  (running-build bt "amazonica" "amazonica" "0.3.132")

  (analysis-requested! bt "leefoo" "leebar" "0.8.0")

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

  (sql/update! db :builds {:analysis_job_uri "hello world"} {:id 9})

  (jdbc/execute-one! db-spec
                     ["INSERT INTO builds (group_id, artifact_id, version, analysis_requested_ts) VALUES (?,?,?,?) RETURNING id"
                      "leeg" "leea" "1.2.3" (now)]
                     {:builder-fn rs/as-unqualified-maps})
  ;; => {:id 68832}

  (analysis-requested! bt "bidi" "bidi" "2.1.3")

  (track-analysis-kick-off! db 2 "xxx"))


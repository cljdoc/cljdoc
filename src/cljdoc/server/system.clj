(ns cljdoc.server.system
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.server.release-monitor]
            [cljdoc.server.clojars-stats]
            [cljdoc.server.pedestal]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.storage.postgres-impl :as postgres]
            [cljdoc.storage.sqlite-impl :as sqlite]
            [cljdoc.util.sentry]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sqlite-cache :as sqlite-cache]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [unilog.config :as unilog]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]
            [ragtime.clj.core] ;; allow Clojure-based migrations
            [taoensso.nippy :as nippy]
            [tea-time.core :as tt]))

(unilog/start-logging!
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"]
  :appenders (when (cfg/sentry-dsn)
               [{:appender :sentry}])})

(defn index-dir [env-config]
  (str (cfg/data-dir env-config) "index-lucene91"))

(defn system-config [env-config]
  (let [ana-service (cfg/analysis-service env-config)]
    (doseq [ns (cfg/extension-namespaces env-config)]
      (log/info "Loading extension namespace" ns)
      (require ns))
    (merge
     {:cljdoc/tea-time        {}
      :cljdoc/sqlite          {:db-spec (cfg/db env-config)
                               :dir     (cfg/data-dir env-config)}
      :cljdoc/postgres         {:db-spec (cfg/postgres-db env-config)}
      :cljdoc/cache           (merge (cfg/cache env-config)
                                     {:cache-dir      (cfg/data-dir env-config)
                                      :key-prefix     "get-pom-xml"
                                      :serialize-fn   nippy/freeze
                                      :deserialize-fn nippy/thaw})
      :cljdoc/searcher {:index-dir       (index-dir env-config)
                        :enable-indexer? (cfg/enable-artifact-indexer? env-config)
                        :tea-time        (ig/ref :cljdoc/tea-time)}
      :cljdoc/pedestal {:port             (cfg/get-in env-config [:cljdoc/server :port])
                        :host             (get-in env-config [:cljdoc/server :host])
                        :build-tracker    (ig/ref :cljdoc/build-tracker)
                        :analysis-service (ig/ref :cljdoc/analysis-service)
                        :storage          (ig/ref :cljdoc/storage)
                        :cache            (ig/ref :cljdoc/cache)
                        :searcher         (ig/ref :cljdoc/searcher)}
      :cljdoc/storage       {:sqlite-spec   (ig/ref :cljdoc/sqlite)
                             :postgres-spec (ig/ref :cljdoc/postgres)
                             :active-db     (get-in env-config [:cljdoc/server :active-db])}
      :cljdoc/build-tracker {:db-spec (ig/ref :cljdoc/sqlite)} ;TODO move to Postgres
      :cljdoc/analysis-service {:service-type ana-service
                                :opts (merge
                                       {:repos (->> (cfg/maven-repositories)
                                                    (map (fn [{:keys [id url]}] [id {:url url}]))
                                                    (into {}))}
                                       (when (= ana-service :circle-ci)
                                         (cfg/circle-ci env-config)))}
      :cljdoc/clojars-stats   {:db-spec (ig/ref :cljdoc/sqlite) ;TODO move to Postgres
                               :retention-days (cfg/get-in env-config [:cljdoc/server :clojars-stats-retention-days])
                               :tea-time (ig/ref :cljdoc/tea-time)}}

     (when (cfg/enable-release-monitor? env-config)
       {:cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/sqlite)  ;TODO move to Postgres
                                 :dry-run? (not (cfg/autobuild-clojars-releases? env-config))
                                 :searcher (ig/ref :cljdoc/searcher)
                                 :tea-time (ig/ref :cljdoc/tea-time)}}))))

(defmethod ig/init-key :cljdoc/analysis-service [k {:keys [service-type opts]}]
  (log/info "Starting" k)
  (case service-type
    :circle-ci (analysis-service/circle-ci opts)
    :local     (analysis-service/map->Local opts)))

(defmethod ig/init-key :cljdoc/storage [k {:keys [postgres-spec sqlite-spec active-db]}]
  (log/info "Starting" k)
  (log/info "Using storage mode" active-db)
  (if (= active-db :sqlite)
    (sqlite/->SQLiteStorage sqlite-spec)
    (postgres/->PostgresStorage postgres-spec)))

(defmethod ig/init-key :cljdoc/tea-time [k _]
  (log/info "Starting" k)
  (tt/start!))

(defmethod ig/halt-key! :cljdoc/tea-time [k _]
  (log/info "Starting" k)
  (tt/stop!))

(defmethod ig/init-key :cljdoc/build-tracker [k {:keys [db-spec]}]
  (log/info "Starting" k)
  (build-log/->SQLBuildTracker db-spec))

(defn run-migrations! [db-spec dir]
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources dir)
                       {:reporter (fn [_store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))}))

(defmethod ig/init-key :cljdoc/postgres [_ {:keys [db-spec]}]
  (run-migrations! db-spec (:migrations-dir db-spec))
  db-spec)

(defmethod ig/init-key :cljdoc/sqlite [_ {:keys [db-spec dir]}]
  (.mkdirs (io/file dir))
  (run-migrations! db-spec (:migrations-dir db-spec))
  db-spec)

(defmethod ig/init-key :cljdoc/cache [_ {:keys [cache-dir] :as cache-opts}]
  (.mkdirs (io/file cache-dir))
  {:cljdoc.util.repositories/get-pom-xml (sqlite-cache/memo-sqlite repos/get-pom-xml cache-opts)})

(defn -main []
  (integrant.core/init
   (cljdoc.server.system/system-config
    (cfg/config)))
  (deref (promise)))

(comment
  ;; This is the main REPL entry point into cljdoc.
  ;; Run the forms one by one up to `go` and you should
  ;; have a running server at http://localhost:8000
  ;; OR run then all at once:
  (do
    (require '[cljdoc.server.system])
    (in-ns 'cljdoc.server.system)
    (require '[integrant.repl])
    (integrant.repl/set-prep! #(system-config (cfg/config)))
    (integrant.repl/go))

  ;;playground for postgres ragtime migration
  (require '[ragtime.repl :as r])
  (def pg-db {:dbtype "postgresql"
              :dbname "cljdoc"
              :host "localhost"
              :user "postgres"
              :password "pass"
              :ssl false
              :sslfactory "org.postgresql.ssl.NonValidatingFactory"})
  (def config {:datastore  (ragtime.jdbc/sql-database pg-db)
               :migrations (ragtime.jdbc/load-resources "postgres_migrations")})

  (r/migrate config)
  (r/rollback config 1)

  (require '[integrant.repl]
           '[clojure.spec.test.alpha :as st])

  (integrant.repl/set-prep! #(system-config (cfg/config)))

  (taoensso.tufte/add-basic-println-handler! {})

  (integrant.repl/go)

  (do (integrant.repl/halt)
      (st/instrument)
      (integrant.repl/go))

  (integrant.repl/reset)

  ;; To invoke a URL in a started system
  integrant.repl.state/system
  (do
    (require '[io.pedestal.test :as pdt])
    (pdt/response-for
     (get-in integrant.repl.state/system [:cljdoc/pedestal :io.pedestal.http/service-fn])
     :get "/api/search?q=async" #_:body :headers {"Accept" "*/*"}))

  nil)

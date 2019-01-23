(ns cljdoc.server.system
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.server.release-monitor]
            [cljdoc.server.pedestal]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.storage.api :as storage]
            [cljdoc.util.sentry]
            [cljdoc.util.repositories :as repos]
            [cljdoc.util.sqlite-cache :as sqlite-cache]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [unilog.config :as unilog]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]
            [taoensso.nippy :as nippy]))

(unilog/start-logging!
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"]
  :appenders (when (cfg/sentry-dsn)
               [{:appender :sentry}])})

(defn system-config [env-config]
  (let [ana-service (cfg/analysis-service env-config)
        port        (cfg/get-in env-config [:cljdoc/server :port])]
    (merge
     {:cljdoc/sqlite          {:db-spec (cfg/db env-config)
                               :dir     (cfg/data-dir env-config)}
      :cljdoc/cache           (merge (cfg/cache env-config)
                                     {:cache-dir      (cfg/data-dir env-config)
                                      :key-prefix     "get-pom-xml"
                                      :serialize-fn   nippy/freeze
                                      :deserialize-fn nippy/thaw})
      :cljdoc/pedestal {:port             (cfg/get-in env-config [:cljdoc/server :port])
                        :host             (get-in env-config [:cljdoc/server :host])
                        :build-tracker    (ig/ref :cljdoc/build-tracker)
                        :analysis-service (ig/ref :cljdoc/analysis-service)
                        :storage          (ig/ref :cljdoc/storage)
                        :cache            (ig/ref :cljdoc/cache)}
      :cljdoc/storage       {:db-spec (ig/ref :cljdoc/sqlite)}
      :cljdoc/build-tracker {:db-spec (ig/ref :cljdoc/sqlite)}
      :cljdoc/analysis-service {:service-type ana-service
                                :opts (merge
                                       {:repos (->> (cfg/maven-repositories)
                                                    (map (fn [{:keys [id url]}] [id {:url url}]))
                                                    (into {}))}
                                       (when (= ana-service :circle-ci)
                                         (cfg/circle-ci env-config)))}}

     (when (cfg/enable-release-monitor? env-config)
       {:cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/sqlite)
                                 :dry-run? (not (cfg/autobuild-clojars-releases? env-config))}}))))

(defmethod ig/init-key :cljdoc/analysis-service [k {:keys [service-type opts]}]
  (log/info "Starting" k (:analyzer-version opts))
  (case service-type
    :circle-ci (analysis-service/circle-ci opts)
    :local     (analysis-service/map->Local opts)))

(defmethod ig/init-key :cljdoc/storage [k {:keys [db-spec]}]
  (log/info "Starting" k)
  (storage/->SQLiteStorage db-spec))

(defmethod ig/init-key :cljdoc/build-tracker [k {:keys [db-spec]}]
  (log/info "Starting" k)
  (build-log/->SQLBuildTracker db-spec))

(defmethod ig/init-key :cljdoc/sqlite [_ {:keys [db-spec dir]}]
  (.mkdirs (io/file dir))
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources "migrations")
                       {:reporter (fn [store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))})
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
  (require '[integrant.repl]
           '[clojure.spec.test.alpha :as st])

  (integrant.repl/set-prep! #(system-config (cfg/config)))

  (integrant.repl/go)

  (do (integrant.repl/halt)
      (st/instrument)
      (integrant.repl/go))

  (integrant.repl/reset)

  )

(ns cljdoc.server.system
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.server.release-monitor]
            [cljdoc.server.pedestal]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.storage.api :as storage]
            [cljdoc.util.sentry]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [cognician.dogstatsd :as dogstatsd]
            [integrant.core :as ig]
            [unilog.config :as unilog]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]))

(unilog/start-logging!
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"]
  :appenders (when (cfg/sentry-dsn)
               [{:appender :sentry}])})

(defn system-config [env-config]
  (let [ana-service (cfg/analysis-service env-config)
        port        (cfg/get-in env-config [:cljdoc/server :port])]
    {:cljdoc/sqlite          {:db-spec (cfg/db env-config)
                              :dir     (cfg/data-dir env-config)}
     ;;:cljdoc/cache           {:cache-dir (cfg/data-dir env-config)}
     :cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/sqlite)
                              ;;:cache    (ig/ref :cljdoc/cache)
                              :dry-run? (not (cfg/autobuild-clojars-releases? env-config))}
     :cljdoc/pedestal {:port             (cfg/get-in env-config [:cljdoc/server :port])
                       :host             (get-in env-config [:cljdoc/server :host])
                       :build-tracker    (ig/ref :cljdoc/build-tracker)
                       :analysis-service (ig/ref :cljdoc/analysis-service)
                       :storage          (ig/ref :cljdoc/storage)
                       ;;:cache            (ig/ref :cljdoc/cache)
                       }
     :cljdoc/storage       {:db-spec (ig/ref :cljdoc/sqlite)}
     :cljdoc/build-tracker {:db-spec (ig/ref :cljdoc/sqlite)}
     :cljdoc/analysis-service {:service-type ana-service
                               :opts (merge
                                      {:repos (->> (cfg/maven-repositories)
                                                   (map (fn [{:keys [id url]}] [id {:url url}]))
                                                   (into {}))}
                                      (when (= ana-service :circle-ci)
                                        (cfg/circle-ci env-config)))}
     :cljdoc/dogstats (cfg/statsd env-config)}))

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

(defmethod ig/init-key :cljdoc/dogstats [_ {:keys [uri tags]}]
  (dogstatsd/configure! uri {:tags tags}))

;; (defmethod ig/init-key :cljdoc/cache [_ {:keys [cache-dir]}]
;;   (.mkdirs (io/file cache-dir)))

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

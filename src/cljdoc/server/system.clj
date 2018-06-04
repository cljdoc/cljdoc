(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.server.release-monitor]
            [cljdoc.server.pedestal]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [yada.yada :as yada]
            [unilog.config :as unilog]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]))

(unilog/start-logging!
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"
            #_{:file "/var/log/standard-json.log" :encoder :json}]})

(defn system-config [env-config]
  (let [ana-service (cfg/analysis-service env-config)
        port        (cfg/get-in env-config [:cljdoc/server :port])]
    {:cljdoc/sqlite          {:db-spec (cfg/build-log-db env-config)
                              :dir     (cfg/data-dir env-config)}
     :cljdoc/build-tracker   (ig/ref :cljdoc/sqlite)
     :cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/sqlite)
                              :dry-run? (not (cfg/autobuild-clojars-releases? env-config))}
     ;; :cljdoc/server  {:port    port,
     ;;                  :handler (ig/ref :cljdoc/handler)}
     ;; :cljdoc/handler {:dir (cfg/get-in env-config [:cljdoc/server :dir])
     ;;                  :build-tracker (ig/ref :cljdoc/build-tracker)
     ;;                  :analysis-service (ig/ref :cljdoc/analysis-service)}
     :cljdoc/pedestal {:port             (cfg/get-in env-config [:cljdoc/server :port])
                       :host             (get-in env-config [:cljdoc/server :host])
                       :build-tracker    (ig/ref :cljdoc/build-tracker)
                       :analysis-service (ig/ref :cljdoc/analysis-service)
                       ;; todo just pass storage component
                       ;; see https://github.com/martinklepsch/cljdoc/issues/58
                       :dir              (cfg/data-dir env-config)}
     :cljdoc/analysis-service (case ana-service
                                :local     [:local {:full-build-url (str "http://localhost:" port "/api/full-build")}]
                                :circle-ci [:circle-ci (cfg/circle-ci env-config)])}))

(defmethod ig/init-key :cljdoc/server [_ {:keys [handler port] :as opts}]
  (log/info "Starting server on port" port)
  (yada/listener handler {:port port}))

(defmethod ig/halt-key! :cljdoc/server [_ server]
  ((:close server)))

(defmethod ig/init-key :cljdoc/handler [_ opts]
  (cljdoc.server.handler/cljdoc-routes opts))

(defmethod ig/init-key :cljdoc/analysis-service [_ [type opts]]
  (log/infof "Starting Analysis Service %s" type)
  (case type
    :circle-ci (analysis-service/circle-ci (:api-token opts) (:builder-project opts))
    :local     (analysis-service/->Local (:full-build-url opts))))

(defmethod ig/init-key :cljdoc/sqlite [_ {:keys [db-spec dir]}]
  (.mkdirs (io/file dir))
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources "migrations")
                       {:reporter (fn [store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))})
  db-spec)

(comment
  (require '[integrant.repl])

  (integrant.repl/set-prep! #(system-config (cfg/config)))

  (integrant.repl/go)

  (integrant.repl/halt)

  (integrant.repl/reset)

  )

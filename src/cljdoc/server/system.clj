(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.server.release-monitor]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [yada.yada :as yada]
            [unilog.config :as unilog]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]))

(def logging-config
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"
            #_{:file "/var/log/standard-json.log" :encoder :json}]})

(defn system-config [env-config]
  (let [ana-service (cfg/analysis-service)
        port        (cfg/get-in env-config [:cljdoc/server :port])]
    {:cljdoc/logger          logging-config
     :cljdoc/sqlite          (cfg/build-log-db)
     :cljdoc/build-tracker   (ig/ref :cljdoc/sqlite)
     :cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/sqlite)
                              :dry-run? (not (cfg/autobuild-clojars-releases?))}
     :cljdoc/server  {:port    port,
                      :handler (ig/ref :cljdoc/handler)}
     :cljdoc/handler {:dir (cfg/get-in env-config [:cljdoc/server :dir])
                      :build-tracker (ig/ref :cljdoc/build-tracker)
                      :analysis-service (ig/ref :cljdoc/analysis-service)}
     :cljdoc/analysis-service (case ana-service
                                :local     [:local {:full-build-url (str "http://localhost:" port "/api/full-build")}]
                                :circle-ci [:circle-ci (cfg/circle-ci)])}))

(defmethod ig/init-key :cljdoc/logger [_ opts]
  (unilog/start-logging! opts))

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

(defmethod ig/init-key :cljdoc/sqlite [_ db-spec]
  (.mkdirs (io/file (cfg/data-dir)))
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

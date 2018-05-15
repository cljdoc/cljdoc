(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [yada.yada :as yada]
            [unilog.config :as unilog]))

(def logging-config
 {:level   :info
  :console true
  :files   ["log/cljdoc.log"
            #_{:file "/var/log/standard-json.log" :encoder :json}]})

(defn system-config [env-config]
  {:cljdoc/server  {:port    (cfg/get-in env-config [:cljdoc/server :port]),
                    :handler (ig/ref :cljdoc/handler)}
   :cljdoc/handler (-> {}
                       (assoc :dir (cfg/get-in env-config [:cljdoc/server :dir]))
                       (assoc :analysis-service (ig/ref :cljdoc/analysis-service))
                       (assoc :s3-deploy (cfg/s3-deploy)))
   :cljdoc/analysis-service [:circle-ci (cfg/circle-ci)]})

(defmethod ig/init-key :cljdoc/server [_ {:keys [handler port] :as opts}]
  (unilog/start-logging! logging-config)
  (log/info "Starting server on port" port)
  (yada/listener handler {:port port}))

(defmethod ig/halt-key! :cljdoc/server [_ server]
  ((:close server)))

(defmethod ig/init-key :cljdoc/handler [_ opts]
  (cljdoc.server.handler/cljdoc-routes opts))

(defmethod ig/init-key :cljdoc/analysis-service [_ [type opts]]
  (case type
    :circle-ci (analysis-service/circle-ci (:api-token opts) (:builder-project opts))
    :local     (analysis-service/->Local)))

(comment
  (require '[integrant.repl])

  (integrant.repl/set-prep! #(system-config (cfg/config)))

  (integrant.repl/go)

  (integrant.repl/halt)

  (integrant.repl/reset)

  )

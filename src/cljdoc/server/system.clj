(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [cljdoc.config :as cfg]
            [integrant.core :as ig]
            [yada.yada :as yada]))

(defn system-config [env-config]
  {:cljdoc/server  {:port    (cfg/get-in env-config [:cljdoc/server :port]),
                    :handler (ig/ref :cljdoc/handler)}
   :cljdoc/handler (-> (select-keys env-config [:circle-ci])
                       (assoc :dir (cfg/get-in env-config [:cljdoc/server :dir]))
                       (assoc :deploy-bucket (-> (cfg/get-in env-config [:aws])
                                                 (select-keys [:access-key :secret-key :s3-bucket-name :cloudfront-id]))))})

(defmethod ig/init-key :cljdoc/server [_ {:keys [handler port] :as opts}]
  (println "Starting server on port" port)
  (yada/listener handler {:port port}))

(defmethod ig/halt-key! :cljdoc/server [_ server]
  ((:close server)))

(defmethod ig/init-key :cljdoc/handler [_ opts]
  (cljdoc.server.handler/cljdoc-api-routes opts))

(comment
  (require '[integrant.repl])

  (integrant.repl/set-prep! #(system-config (cfg/config :default)))

  (integrant.repl/go)

  (integrant.repl/halt)

  (integrant.repl/reset)

  )

(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [cljdoc.config :as cfg]
            [integrant.core :as ig]
            [yada.yada :as yada]))

(defn system-config [env-config]
  {:cljdoc/server  {:port    (cfg/get-in env-config [:cljdoc/server :port]),
                    :handler (ig/ref :cljdoc/handler)}
   :cljdoc/handler {:name "Alice"}})

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

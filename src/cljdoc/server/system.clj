(ns cljdoc.server.system
  (:require [cljdoc.server.handler]
            [integrant.core :as ig]
            [yada.yada :as yada]))

(defn system-config [env-config]
  {:cljdoc/server  {:port 8080, :handler (ig/ref :cljdoc/handler)}
   :cljdoc/handler {:name "Alice"}})

(defmethod ig/init-key :cljdoc/server [_ {:keys [handler port] :as opts}]
  (yada/listener handler {:port port}))

(defmethod ig/halt-key! :cljdoc/server [_ server]
  ((:close server)))

(defmethod ig/init-key :cljdoc/handler [_ opts]
  (cljdoc.server.handler/cljdoc-api-routes opts))

(comment
  (require '[integrant.repl :as igr])

  (igr/set-prep! #(system-config {}))

  (igr/go)

  (igr/halt)

  (igr/reset)

  )

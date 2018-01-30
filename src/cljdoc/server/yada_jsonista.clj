(ns cljdoc.server.yada-jsonista
  (:require [yada.request-body :as req-body]
            [yada.body :as res-body]
            [byte-streams :as bs]
            [jsonista.core :as json]))

(defmethod req-body/parse-stream "application/json"
  [_ stream]
  (-> (bs/to-string stream)
      (json/read-value)
      (req-body/with-400-maybe)))

(defmethod req-body/process-request-body "application/json"
  [& args]
  (apply req-body/default-process-request-body args))

(ns cljdoc.server.handler
  (:require [yada.yada :as yada]
            [yada.request-body :as req-body]
            [yada.body :as res-body]
            [byte-streams :as bs]
            [jsonista.core :as json]))

;; JSONista
;; TODO turn this into cljdoc.server/yada-jsonista

(defmethod req-body/parse-stream "application/json"
  [_ stream]
  (-> (bs/to-string stream)
      (json/read-value)
      (req-body/with-400-maybe)))

(defmethod req-body/process-request-body "application/json"
  [& args]
  (apply req-body/default-process-request-body args))

(defn auth [[user password]]
  (let [m {"cljdoc" "cljdoc"}]
    (if (get m user)
      {:user  user
       :roles #{:api-user}}
      {})))

(def circle-ci-webhook-handler
  (yada/handler
   (yada/resource
    {:access-control
     {:realm "accounts"
      :scheme "Basic"
      :verify auth
      :authorization {:methods {:post :api-user}}}

     :methods
     {:post
      {:consumes #{"application/json"}
       :produces "text/plain"
       :response (fn [ctx]
                   (clojure.pprint/pprint (:body ctx))
                   (assoc-in ctx [:response :status]))}}})))

(def trigger-analysis-handler
  (yada/handler
   (yada/resource
    {:methods
     {:post
      {:parameters {:form {:project String :version String :jarpath String}}
       :consumes #{"application/x-www-form-urlencoded"}
       :response (fn [ctx]
                   (prn (get-in ctx [:parameters :form]))
                   (assoc-in ctx [:response :status] 200))}}})))

(def ping-handler
  (yada/handler
   (yada/resource
    {:methods
     {:get
      {:produces "text/plain"
       :response "pong"}}})))

(defn cljdoc-api-routes [deps]
  (println 'passed-handler-opts deps)
  ["" [["/ping"             ping-handler]
       ["/hooks/circle-ci"  circle-ci-webhook-handler]
       ["/request-analysis" trigger-analysis-handler]]])

(comment

  (start-server!)

  (stop-server!)

  )

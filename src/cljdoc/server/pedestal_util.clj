(ns cljdoc.server.pedestal-util
  "Based on this excellent guide: http://pedestal.io/guides/hello-world-content-types"
  (:require [cheshire.core :as json]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.content-negotiation :as conneg]))

(def negotiate-content conneg/negotiate-content)

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "text/html"))

(defn transform-content
  ([body content-type] transform-content body content-type nil)
  ([body content-type transformer]
   (let [body' ((or transformer identity) body)]
     (case content-type
       "text/html"                      (str body')
       "application/edn"                (pr-str body')
       "application/json"               (json/generate-string body')
       "application/x-suggestions+json" (json/generate-string body')))))

(defn coerce-to
  ([response content-type] (coerce-to response content-type nil))
  ([response content-type transformer]
   (-> response
       (update :body transform-content content-type transformer)
       (assoc-in [:headers "Content-Type"] content-type))))

(defn coerce-body-conf
  "Coerce the `:response :body` to the `accepted-type`, optionally passing it
  through the `transformer`, a `(fn [request content-type body] (modify body))`
  See [[transform-content]]."
  [transformer]
  (interceptor/interceptor
    {:name  ::coerce-body
     :leave (fn [context]
              (let [content-type (accepted-type context)
                    transformer' (when transformer
                                   (partial transformer (:request context) content-type))]
                (cond-> context
                        (nil? (get-in context [:response :headers "Content-Type"]))
                        (update-in [:response] coerce-to content-type transformer'))))}))

(def coerce-body
  (coerce-body-conf nil))

(defn ok
  "Return the context `ctx` with response `body` and status 200"
  [ctx body]
  (assoc ctx :response {:status 200 :body body}))

(defn ok-html
  "Return the context `ctx` with response `body`, status 200
  and the Content-Type header set to `text/html`"
  [ctx body]
  (assoc ctx :response {:status 200
                        :body (str body)
                        :headers {"Content-Type" "text/html"}}))

(defn ok-xml
  "Return the context `ctx` with response `body`, status 200
  and the Content-Type header set to `text/xml`"
  [ctx body]
  (assoc ctx :response {:status 200
                        :body (str body)
                        :headers {"Content-Type" "text/xml"}}))

(defn html
  "Return an interceptor that will pass the context to the provided
  function `render-fn` and return it's result as a text/html response."
  [render-fn]
  (interceptor/interceptor
   {:name ::html
    :enter (fn html-render-inner [context]
             (ok-html context (render-fn context)))}))

(defn body
  "Return an interceptor that will pass the context to the provided
  function `body-fn` and return it's result as the body of an OK response."
  [body-fn]
  (interceptor/interceptor
    {:name ::body
     :enter (fn body-render-inner [context]
              (ok context (body-fn context)))}))

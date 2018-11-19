(ns cljdoc.server.pedestal-util
  "Based on this excellent guide: http://pedestal.io/guides/hello-world-content-types"
  (:require [cheshire.core :as json]
            [io.pedestal.http.content-negotiation :as conneg]))

(def negotiate-content conneg/negotiate-content)

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "text/html"))

(defn transform-content
  [body content-type]
  (case content-type
    "text/html"        (str body)
    "application/edn"  (pr-str body)
    "application/json" (json/generate-string body)))

(defn coerce-to
  [response content-type]
  (-> response
      (update :body transform-content content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(def coerce-body
  {:name ::coerce-body
   :leave (fn [context]
            (cond-> context
              (nil? (get-in context [:response :body :headers "Content-Type"]))
              (update-in [:response] coerce-to (accepted-type context))))})

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
  {:name ::html
   :enter (fn html-render-inner [context]
            (ok-html context (render-fn context)))})

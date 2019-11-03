(ns cljdoc.server.pedestal-util
  "Based on this excellent guide: http://pedestal.io/guides/hello-world-content-types"
  (:require [cheshire.core :as json]
            [io.pedestal.interceptor :as interceptor]
            [io.pedestal.http.content-negotiation :as conneg]))

(def negotiate-content conneg/negotiate-content)

(defn accepted-type
  [context]
  (get-in context [:request :accept :field] "text/html"))

(defn- render-body
  [body content-type]
  (case content-type
    "text/html"                      (str body)
    "application/edn"                (pr-str body)
    "application/json"               (json/generate-string body)
    "application/x-suggestions+json" (json/generate-string body)))

(defn- coerce-to
  [response content-type]
  (-> response
      (update :body render-body content-type)
      (assoc-in [:headers "Content-Type"] content-type)))

(defn coerce-body-conf
  "Coerce the `:response :body` to the `accepted-type`, passing it through `html-render-fn`
  if the resulting content type should be `text/html`. `html-render-fn` will receive the request
  `context` as its only argument.

  Maybe HTML rendering could also be handled in a separate interceptor that uses [[accepted-type]]
  to conditionally convert the data provided via `:body` to it's HTML representation."
  [html-render-fn]
  (interceptor/interceptor
    {:name  ::coerce-body
     :leave (fn [context]
              (if (get-in context [:response :headers "Content-Type"])
                context
                (let [content-type (accepted-type context)
                      rendered-body (if (= content-type "text/html")
                                      (html-render-fn context)
                                      (-> context :response :body))]
                  (-> context
                      (assoc-in [:response :body] rendered-body)
                      (update :response coerce-to content-type)))))}))

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

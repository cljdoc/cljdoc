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

(ns cljdoc.render.offline-url
  "Share-able code for computing offline urls"
  (:require [clojure.string :as string]))

(defn ns-url
  [ns]
  {:pre [(string? ns)]}
  (str "api/" ns ".html"))

(defn article-url
  [slug-path]
  {:pre [(string? (first slug-path))]}
  ;; WARN this could lead to overwriting files but nesting
  ;; directories complicates linking between files a lot and
  ;; so taking a shortcut here.
  (str "doc/" (string/join "-" slug-path) ".html"))

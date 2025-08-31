(ns cljdoc.server.built-assets
  (:require [clojure.edn :as edn]))

(defn load
  "Load client side asset map.
   E.g. /cljdoc.js -> /cljdoc.db58f58a.js"
  []
  (-> "resources-compiled/manifest.edn" slurp edn/read-string))

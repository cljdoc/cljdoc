(ns cljdoc.render.external-libs
  (:require [clojure.string :as string]))

;; TODO: extend and reference from layout.clj and offline.clj after merge of asciidoc PR
;; TODO: consider: better as .edn file?

(def libs
  [{:name "highlight.js" :version "9.12.0" }
   {:name "tachyons"    :version "4.9.0"  }])

(defn get-npm-lib-versions
  "Called by version checking bash script"
  []
  (->> libs
       (map #(str (:name %) "@" (:version %)))
       (string/join " ")))

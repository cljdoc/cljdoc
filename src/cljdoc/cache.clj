(ns cljdoc.cache
  (:require [cljdoc.spec]))

(defn namespaces
  "Return entity-maps for all namespaces in the cache-bundle"
  [{:keys [cache-contents cache-id]}]
  (let [has-defs? (fn [ns-emap]
                    (seq (filter #(= (:namespace ns-emap) (:namespace %))
                                 (:defs cache-contents))))]
    (->> (:namespaces cache-contents)
         (map :name)
         (map #(merge cache-id {:namespace %}))
         (filter has-defs?)
         (map #(cljdoc.spec/assert :cljdoc.spec/namespace-entity %))
         set)))

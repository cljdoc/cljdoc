(ns cljdoc.bundle
  "Functions to operate on cache bundles"
  (:require [cljdoc.spec]
            [cljdoc.platforms :as platf]
            [clojure.string :as string]))

(defn ns-entities
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

(defn- platf-name [p]
  (string/lower-case (platf/get-field p :name)))

(defn namespaces
  [{:keys [cache-contents cache-id]}]
  {:pre [(find cache-contents :namespaces)]}
  (->> (:namespaces cache-contents)
       (group-by :name)
       (vals)
       (map platf/unify-namespaces)
       (sort-by platf-name)))

(defn defs-for-ns
  [all-defs ns]
  (->> all-defs
       (filter #(= ns (:namespace %)))
       (group-by :name)
       (vals)
       (map platf/unify-defs)
       (sort-by platf-name)))

(comment
  (defn cb [id]
    (cljdoc.storage.api/bundle-docs (cljdoc.storage.api/->GrimoireStorage (clojure.java.io/file "data" "grimoire")) id))

  (->> (cb {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"})
       namespaces)

  )

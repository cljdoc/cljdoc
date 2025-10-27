(ns cljdoc.util.api-analysis)

(defn- index-by [k xs]
  (->> (for [[k vs] (group-by k xs)]
         (if (second vs)
           (throw (ex-info (format "Duplicate item for key %s: (files: %s)" k (mapv :file vs)) {:vs vs}))
           [k (first vs)]))
       (into {})))

(defn- assert-no-duplicate-publics [namespaces]
  (doseq [ns namespaces]
    (index-by :name (:publics ns))))

(defn sanitize-macros
  [{:strs [clj cljs] :as api-analysis}]
  (assert-no-duplicate-publics clj)
  (assert-no-duplicate-publics cljs)
  api-analysis)

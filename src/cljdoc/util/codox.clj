(ns cljdoc.util.codox)

(defn- index-by [k xs]
  (->> (for [[k vs] (group-by k xs)]
         (if (second vs)
           (throw (ex-info (format "Duplicate item for key %s: (files: %s)" k (mapv :file vs)) {:vs vs}))
           [k (first vs)]))
       (into {})))

(defn assert-no-duplicate-publics [namespaces]
  (doseq [ns namespaces]
    (index-by :name (:publics ns))))

(defn sanitize-macros
  ;; TODO delete & replace with just the assertion stuff from above
  [{:strs [clj cljs] :as codox}]
  (assert-no-duplicate-publics clj)
  (assert-no-duplicate-publics cljs)
  codox)

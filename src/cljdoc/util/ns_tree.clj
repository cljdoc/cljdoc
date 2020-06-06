(ns cljdoc.util.ns-tree
  (:require [clojure.string :as string]))
;; bunch of this is taken from https://github.com/weavejester/codox/blob/da92057b9c904f6e35078cc680c2422db76521b0/codox/src/codox/writer/html.clj#L165-L199https://github.com/weavejester/codox/blob/da92057b9c904f6e35078cc680c2422db76521b0/codox/src/codox/writer/html.clj#L165-L199
;; TODO proper credit

(defn split-ns [namespace]
  (string/split (str namespace) #"\."))

(defn- namespace-parts [namespace]
  (->> (split-ns namespace)
       (reductions #(str %1 "." %2))
       #_(map symbol)))

(defn- add-depths [namespaces]
  (->> namespaces
       (map (juxt identity (comp count split-ns)))
       (reductions (fn [[_ ds] [ns d]] [ns (cons d ds)]) [nil nil])
       (rest)))

(defn- add-heights [namespaces]
  (for [[ns ds] namespaces]
    (let [d (first ds)
          h (count (take-while #(not (or (= d %) (= (dec d) %))) (rest ds)))]
      [ns d h])))

(defn- add-branches [namespaces]
  (->> (partition-all 2 1 namespaces)
       (map (fn [[[ns d0 h] [_ d1 _]]] [ns d0 h (= d0 d1)]))))

(defn namespace-hierarchy [namespaces]
  (->> namespaces
       (sort)
       (mapcat namespace-parts)
       (distinct)
       (add-depths)
       (add-heights)
       (add-branches)))

(defn index-by [f m]
  (into {} (map (juxt f identity) m)))

(comment
  (def d '("instaparse.core" "instaparse.transform" "instaparse.transform.test" "instaparse.reduction" "instaparse.abnf" "instaparse.failure" "instaparse.repeat" "instaparse.macros" "instaparse.combinators" "instaparse.auto-flatten-seq" "instaparse.viz" "instaparse.util" "instaparse.print" "instaparse.line-col" "instaparse.gll" "instaparse.combinators-source" "instaparse.cfg"))

  (map namespace-parts d)

  (clojure.pprint/pprint
   (namespace-hierarchy d)))


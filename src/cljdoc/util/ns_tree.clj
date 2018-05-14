(ns cljdoc.util.ns-tree)

(defn split-ns [namespace]
  (clojure.string/split (str namespace) #"\."))

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


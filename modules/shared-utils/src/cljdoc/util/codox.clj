(ns cljdoc.util.codox)

(defn- index-by [k xs]
  (->> (for [[k vs] (group-by k xs)]
         (if (second vs)
           (throw (ex-info (format "Duplicate item for key %s: %s" k vs) {:vs vs}))
           [k (first vs)]))
       (into {})))

(defn- macro? [var]
  (= :macro (:type var)))

(defn sanitize-macros
  "Reading macros from Clojure's and ClojureScripts `ns-publics` may
  return different arglists under certain conditions. Because macros
  are always coming from Clojure anyways we drop any macros that were
  retrieved from ClojureScript's ns-publics.

  Codox currently also makes two passes (clj/cljs) to include macros,
  presumably this was necessary in earlier versions whent he cljs analyzer
  didn't properly return macros as part of `ns-publics`.

  This function is idempotent and returns the analysis data in its
  original shape.

  See https://dev.clojure.org/jira/browse/CLJS-2852"
  [{:strs [clj cljs] :as codox}]
  (if (and clj cljs)
    (let [clj-by-name  (index-by :name clj)
          cljs-by-name (index-by :name cljs)]
      {"clj" clj
       "cljs" (for [[name {:keys [publics] :as ns-data}] cljs-by-name
                    :let [cljs-publics-without-macros (remove macro? publics)
                          clj-macros (->> (get-in clj-by-name [name :publics])
                                          (filter macro?))]]
                (do
                  ;; Sanity check. Ensure we remove macros with the
                  ;; same names as the macros we put back into the data.
                  (assert (= (set (map :name (filter macro? publics)))
                             (set (map :name clj-macros))))
                  (assoc ns-data :publics (into cljs-publics-without-macros clj-macros))))})
    codox))

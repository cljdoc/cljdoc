(ns cljdoc.user-config
  "Users can provide configuration via a file `doc/cljdoc.edn` in their Git repository.

  This namespace defines functions to query the contents of this file."
  (:require [cljdoc.util :as util]))

(defn get-project-specific
  [config-edn project]
  (some->> config-edn
           (filter (fn [[k _]]
                     (and
                      (symbol? k)
                      (or (= k (symbol (util/group-id project)))
                          (= k (symbol (util/group-id project) (util/artifact-id project)))))))
           (first)
           (val)))

(defn get-project
  [config-edn project]
  (or (get-project-specific config-edn project)
      (->> config-edn
           (remove (fn [[k _v]] (symbol? k)))
           (into {}))))

(defn doc-tree [config-edn project]
  (:cljdoc.doc/tree (get-project config-edn project)))

(defn doc-links [config-edn project]
  (:cljdoc.doc/links (get-project config-edn project)))

(defn include-namespaces-from-deps [config-edn project]
  (:cljdoc/include-namespaces-from-dependencies (get-project config-edn project)))

(comment
  (def d
    '{metosin/reitit {:cljdoc.doc/tree [["Introduction" {:file "intro.md"}]]}
      :cljdoc.doc/tree [["Overview" {:file "modules/README.md"}]]})

  (get-project d "metosin/reitit"))

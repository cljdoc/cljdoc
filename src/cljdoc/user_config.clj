(ns cljdoc.user-config
  "Users can provide configuration via a `doc/cljdoc.edn` file in their git
  repository.

  This namespace defines functions to query the contents of this config.

  You'll notice cljdoc looks up sub-projects here.
  This is in support of a single git repository that generates multiple
  artifacts. See the 'Distinctly Configuring' under
  /doc/userguide/for-library-authors.adoc#modules for more details."
  (:require [cljdoc-shared.proj :as proj]))

(defn- project-map-entry? [[k _v]]
  (symbol? k))

(defn- get-project-specific
  [config-edn project]
  (when config-edn
    (or (get config-edn (symbol (proj/group-id project) (proj/artifact-id project)))
        (get config-edn (symbol (proj/group-id project))))))

(defn- get-project
  "Return config for `project`, if `project` key not found, assumes `project`
  is root project and returns that."
  [config-edn project]
  (or (get-project-specific config-edn project)
      (->> config-edn
           (remove project-map-entry?)
           (into {}))))

(defn doc-tree [config-edn project]
  (:cljdoc.doc/tree (get-project config-edn project)))

(defn include-namespaces-from-deps [config-edn project]
  (:cljdoc/include-namespaces-from-dependencies (get-project config-edn project)))

(defn languages [config-edn project]
  (:cljdoc/languages (get-project config-edn project)))

(defn docstring-format [config-edn project]
  (:cljdoc/docstring-format (get-project config-edn project)))

(comment
  (def d
    '{metosin/reitit {:cljdoc.doc/tree [["Introduction" {:file "intro.md"}]]}
      :cljdoc.doc/tree [["Overview" {:file "modules/README.md"}]]})

  (get-project d "metosin/reitit")
  ;; => #:cljdoc.doc{:tree [["Introduction" {:file "intro.md"}]]}

  (get-project d "foo/bar")
  ;; => #:cljdoc.doc{:tree [["Overview" {:file "modules/README.md"}]]}
  )

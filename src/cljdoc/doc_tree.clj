(ns cljdoc.doc-tree
  (:require [clojure.java.io :as io]
            [cuerdas.core :as cuerdas]))

(defn read-file [f]
  (assert (.exists f) (format "File supplied in doctree does not exist %s" f))
  (-> f slurp #_(subs 0 20)))

(declare process-toc)

(defn process-toc-entry [slurp-fn [title attrs & children]]
  (cond-> {:title title}

    (:file attrs)
    (assoc-in [:attrs :cljdoc.doc/source-file] (:file attrs))

    (and (:file attrs) (.endsWith (:file attrs) ".adoc"))
    (assoc-in [:attrs :cljdoc/asciidoc] (slurp-fn (:file attrs)))

    (and (:file attrs) (or (.endsWith (:file attrs) ".md")
                           (.endsWith (:file attrs) ".markdown")))
    (assoc-in [:attrs :cljdoc/markdown] (slurp-fn (:file attrs)))

    (nil? (:slug attrs))
    (assoc-in [:attrs :slug] (cuerdas/uslug title))

    (seq children)
    (assoc :children (process-toc slurp-fn children))))

(defn process-toc [slurp-fn toc]
  (mapv #(process-toc-entry slurp-fn %) toc))

(defn add-slug-path
  "For various purposes it is useful to know the path to a given document
  in it's doc tree. This function adds a field in [:attrs :slug-path] that
  contains the slug of the current document preceded by all slugs of documents
  above it in the tree.

  Probably this could be implemented better using zippers or similar."
  ([doc-tree]
   (map #(add-slug-path % []) doc-tree))
  ([doc-tree base]
   (let [current-slug-path (conj base (get-in doc-tree [:attrs :slug]))]
     (-> doc-tree
         (assoc-in [:attrs :slug-path] current-slug-path)
         (update :children (fn [xs] (map #(add-slug-path % current-slug-path) xs)))))))

(defn flatten*
  "Given a DocTree structure, return all \"nodes\" with children removed."
  [doc-tree]
  (->> (tree-seq coll? seq doc-tree)
       (filter :title)
       (map #(dissoc % :children))))

(defn derive-toc [dir]
  (when-let [readme (->> (.listFiles dir)
                         (map #(.getName %))
                         (filter #(.startsWith (.toLowerCase %) "readme"))
                         first)]
    [["Readme" {:file readme}]]))

(comment

  )

(ns cljdoc.doc-tree
  (:require [clojure.java.io :as io]))

(defn read-file [f]
  (-> f slurp (subs 0 20)))

(defn slugify [s]
  (-> s
      (clojure.string/replace #"\s" "-")
      (clojure.string/lower-case)))

(declare process-toc)

(defn process-toc-entry [base-dir [title attrs & children]]
  (cond-> {:title title}

    (and (:file attrs) (.endsWith (:file attrs) ".adoc"))
    (assoc-in [:attrs :cljdoc/asciidoc] (read-file (io/file base-dir (:file attrs))))

    (and (:file attrs) (or (.endsWith (:file attrs) ".md")
                           (.endsWith (:file attrs) ".markdown")))
    (assoc-in [:attrs :cljdoc/markdown] (read-file (io/file base-dir (:file attrs))))

    (nil? (:slug attrs))
    (assoc-in [:attrs :slug] (slugify title))

    (seq children)
    (assoc :children (process-toc base-dir children))))

(defn process-toc [base-dir toc]
  (mapv #(process-toc-entry base-dir %) toc))

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

(def known-for-testing
  {"yada" {:dir (io/file "/Users/martin/code/02-oss/yada/")
           :toc [["Preface" {:file "doc/preface.adoc"}]
                 ["Basics" {}
                  ["Introduction" {:file "doc/intro.adoc"}]
                  ["Getting Started" {:file "doc/getting-started.adoc"}]
                  ["Hello World" {:file "doc/hello.adoc"}]
                  ["Installation" {:file "doc/install.adoc"}]
                  ["Resources" {:file "doc/install.adoc"}]
                  ["Parameters" {:file "doc/parameters.adoc"}]
                  ["Properties" {:file "doc/properties.adoc"}]
                  ["Methods" {:file "doc/methods.adoc"}]
                  ["Representations" {:file "doc/representations.adoc"}]
                  ["Responses" {:file "doc/responses.adoc"}]
                  ["Security" {:file "doc/security.adoc"}]
                  ["Routing" {:file "doc/routing.adoc"}]
                  ["Phonebook" {:file "doc/phonebook.adoc"}]
                  ["Swagger" {:file "doc/swagger.adoc"}]]
                 ["Advanced Topics" {}
                  ["Async" {:file "doc/async.adoc"}]
                  ["Search Engine" {:file "doc/searchengine.adoc"}]
                  ["Server Sent Events" {:file "doc/sse.adoc"}]
                  ["Chat Server" {:file "doc/chatserver.adoc"}]
                  ["Handling Request Bodies" {:file "doc/requestbodies.adoc"}]
                  ["Selfie Uploader" {:file "doc/selfieuploader.adoc"}]
                  ["Handlers" {:file "doc/handlers.adoc"}]
                  ["Request Context" {:file "doc/requestcontext.adoc"}]
                  ["Interceptors" {:file "doc/interceptors.adoc"}]
                  ["Subresources" {:file "doc/subresources.adoc"}]
                  ["Fileserver" {:file "doc/fileserver.adoc"}]
                  ["Testing" {:file "doc/testing.adoc"}]]
                 ["Reference" {}
                  ["Glossary" {:file "doc/glossary.adoc"}]
                  ["Reference" {:file "doc/reference.adoc"}]
                  ["Colophon" {:file "doc/colophon.adoc"}]]]}})

(comment

  (let [yada (get known-for-testing "yada")]
    (clojure.pprint/pprint
     (flatten*
      (add-slug-path
       (process-toc (:dir yada) (:toc yada))))))

  (spit "test.edn"
        (pr-str (process-toc (:dir yada) (:toc yada))))
  )

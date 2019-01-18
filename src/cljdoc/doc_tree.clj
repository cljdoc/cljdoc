(ns cljdoc.doc-tree
  "Process doctrees provided by projects via a `doc/cljdoc.edn` file.

  Projects can provide a tree of articles via the `:cljdoc.doc/tree` key.
  The data that's provided via this key roughly follows the familiar Hiccup format:

      [[\"Readme\" {:file \"Readme.md\"}
        [\"Child Of Readme\" {:file \"child.md\"}]]]

  Reading the files that are specified in this tree is outside of the
  concern of this namespace and handled via a passed-in function `slurp-fn`.
  Usually this function will read the specified file from a Git repository
  at a specified revision. This was done to keep this namespace free of any
  Git/IO-related aspects.

  The return format is described in the `::doctree` spec that's part of this namespace.

  There also is some additional code `derive-toc` that will return a
  doctree based on a list of files. This is used to derive the doctree
  for projects that haven't provided one explicitly."
  (:require [clojure.spec.alpha :as spec]
            [cuerdas.core :as cuerdas]))

(spec/def :cljdoc.doc/source-file string?)
(spec/def :cljdoc/asiidoc string?)
(spec/def :cljdoc/markdown string?)
(spec/def ::slug string?)
(spec/def ::title string?)
(spec/def ::file string?)

;; Specs for the fully unfurled doctree that is eventually stored in
;; the database. This includes the documents content, slug and more.
(spec/def ::entry
  (spec/keys :req-un [::title]
             :opt-un [::attrs ::children]))

(spec/def ::attrs
  (spec/keys :req-un [::slug]
             :opt [:cljdoc.doc/source-file
                   :cljdoc/asciidoc
                   :cljdoc/markdown]))


(spec/def ::children
  (spec/coll-of ::entry))

(spec/def ::doctree
  (spec/coll-of ::entry))

;; Specs for the Hiccup style configuration format that library authors
;; may use to specify articles and their hierarchy.
(spec/def ::hiccup-attrs
  (spec/keys :opt-un [::file]))

(spec/def ::hiccup-entry
  (spec/spec
   (spec/cat :title ::title
             :attrs (spec/? ::hiccup-attrs)
             :children (spec/* ::hiccup-entry))))

(defmulti filepath->type
  "An extension point for custom doctree items. Dispatching is done based on file extensions.
  The return value is used for two things:

    - It's stored as `:type` in the doctree entry map
    - The contents of a file will be stored at the returned value

  See [[process-toc-entry]] for the specifics."
  (fn [path-str]
    (second (re-find #"\.([^\.]+)$" path-str))))

(defmethod filepath->type "markdown" [_] :cljdoc/markdown)
(defmethod filepath->type "md" [_] :cljdoc/markdown)
(defmethod filepath->type "adoc" [_] :cljdoc/asciidoc)

(defn- process-toc-entry
  [slurp-fn {:keys [title attrs children]}]
  {:pre [(string? title)]}
  ;; If there is a file it has to be matched by filepath->type's dispatch-fn
  ;; Otherwise the line below will throw an exception (intentionally so)
  (let [type (some-> attrs :file filepath->type)]
    (cond-> {:title title}

      (:file attrs)
      (assoc-in [:attrs :cljdoc.doc/source-file] (:file attrs))

      type
      (assoc-in [:attrs type] (slurp-fn (:file attrs)))

      type
      (assoc-in [:attrs :type] type)

      (nil? (:slug attrs))
      (assoc-in [:attrs :slug] (cuerdas/uslug title))

      (seq children)
      (assoc :children (mapv (partial process-toc-entry slurp-fn) children)))))

(spec/fdef process-toc
  :args (spec/cat :slurp-fn fn?
                  :entries (spec/coll-of ::hiccup-entry))
  :ret ::doctree)

(defn process-toc
  "Process a doctree `toc` and inline all file contents via `slurp-fn`.

  The `toc` should be a collection of `::hiccup-entry`s.
  The return value will be a `::doctree`. Individual nodes will have
  an `:attrs` key with additional metadata such as the original file
  name, the file contents and a slug derived from the entry title."
  [slurp-fn toc]
  (let [slurp! (fn [file] (or (slurp-fn file)
                              (throw (Exception. (format "Could not read contents of %s" file)))))]
    (->> toc
         (spec/conform (spec/coll-of ::hiccup-entry))
         (mapv (partial process-toc-entry slurp!)))))

(defn entry->type-and-content
  "Given a single doctree entry return a tuple with the type of the to be rendered document and
  it's content. This is a layer of indirection to enable backwards compatibility with doctrees
  that do not contain a :type key."
  [doctree-entry]
  (if-let [t (:type doctree-entry)]
    [t (get doctree-entry t)]
    (when-let [file (get-in doctree-entry [:attrs :cljdoc.doc/source-file])]
      (assert (filepath->type file) (str "unsupported extension: " file))
      [(filepath->type file)
       (get-in doctree-entry [:attrs (filepath->type file)])])))

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

(defn- subseq?
  "Return true if `ys` is a sequence starting with the elements in `xs`"
  [xs ys]
  (= xs (take (count xs) ys)))

(defn get-subtree
  "Return the node for the given slug-path (including its children).
  Again, this would probably be way simpler with Zippers :)"
  [doc-tree slug-path]
  {:pre [(seq slug-path)]}
  (loop [t doc-tree]
    (cond
      (map? t) (do (assert (:slug-path (:attrs t)) (format "slug-path missing from doc-tree item, title %s" (:title t)))
                   (when (= (:slug-path (:attrs t)) slug-path) t))

      (seq t)  (recur (first (filter #(subseq? (:slug-path (:attrs %)) slug-path) t))))))

;; Deriving doctrees -----------------------------------------------------------

(defn- readme? [path]
  (and (.startsWith (.toLowerCase path) "readme.")
       (try (filepath->type path) (catch Exception _ false))))

(defn- changelog? [path]
  (and (some #(.startsWith (.toLowerCase path) %) ["changelog." "changes."  "history." "news." "releases."])
       (try (filepath->type path) (catch Exception _ false))))

(defn- doc? [path]
  (and (or (.startsWith path "doc/")
           (.startsWith path "docs/"))
       (try (filepath->type path) (catch Exception _ false))))

(defn- infer-title [path file-contents]
  (or (case (filepath->type path)
        ;; NOTE infer-title will fail with non adoc/md files
        :cljdoc/markdown (second (re-find #"(?m)^\s*#+\s*(.*)\s*$" file-contents))
        :cljdoc/asciidoc (second (re-find #"(?m)^\s*=+\s*(.*)\s*$" file-contents)))
      (first (butlast (take-last 2 (cuerdas/split path #"[/\.]"))))
      (throw (ex-info (format "No title found for %s" path)
                      {:path path :contents file-contents}))))

(defn derive-toc
  "Given a list of `files` (as strings) return a doctree that can be
  passed to `process-toc`. By default this function will return a
  doctree consisting of the project's Readme and Changelog as well as
  other files in `doc/` or `docs/`.

  Only files written in supported formats (Markdown or Asciidoc) will
  be taken into account during this process."
  [files]
  (let [readme-path?    (comp readme? :path)
        changelog-path? (comp changelog? :path)
        readme          (first (filter readme-path? files))
        changelog       (first (filter changelog-path? files))]
    (into (cond-> []
            readme    (conj ["Readme" {:file (:path readme)}])
            changelog (conj ["Changelog" {:file (:path changelog)}]))
          (->> (filter #(doc? (:path %)) files)
               (sort-by :path)
               (mapv (fn [{:keys [path object-loader]}]
                      [(infer-title path (slurp object-loader))
                       {:file path}]))))))

(comment

  (derive-toc cljdoc.git-repo/workflo-macros-files)

  (spec/conform
   (spec/coll-of ::hiccup-entry)
   [["Readme"
     ["Example" {:file "x"}]]])

  (process-toc
   identity
   [["Readme" {}
     ["Example" {:file "x"}]]])

  (process-toc-entry
   identity
   (spec/conform ::hiccup-entry ["Changelog" {:file "CHANGELOG.md"}])))

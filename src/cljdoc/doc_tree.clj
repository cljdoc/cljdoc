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
            [clojure.string :as cstr]
            [cuerdas.core :as cuerdas]))

(spec/def ::ne-string (spec/and string?
                                (complement cstr/blank?)))
(spec/def :cljdoc.doc/source-file string?)
(spec/def :cljdoc/asiidoc string?)
(spec/def :cljdoc/markdown string?)
(spec/def :cljdoc.doc/type #{:cljdoc/markdown :cljdoc/asciidoc})
(spec/def :cljdoc/asciidoc string?)
(spec/def :cljdoc.doc/contributors (spec/coll-of string?))
(spec/def ::url
  (spec/with-gen
    (spec/and ::ne-string
              #(try (let [uri (java.net.URI. %)]
                      (and (or (= (cstr/lower-case (.getScheme uri)) "http")
                               (= (cstr/lower-case (.getScheme uri)) "https"))
                           ((complement cstr/blank?) (.getHost uri))))
                    (catch java.net.URISyntaxException _ false)))
    #(spec/gen #{"http://localhost"})))
(spec/def :cljdoc.doc/external-url ::url)
(spec/def ::slug ::ne-string)
(spec/def ::title ::ne-string)
(spec/def ::file string?)

;; Specs for the fully unfurled doctree that is eventually stored in
;; the database. This includes the documents content, slug and more.
(spec/def ::entry
  (spec/keys :req-un [::title]
             :opt-un [::attrs ::children]))

(spec/def ::attrs
  (spec/keys :req-un [::slug]
             :opt [:cljdoc.doc/source-file
                   :cljdoc.doc/type
                   :cljdoc.doc/contributors
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

    - It's stored as `:cljdoc.doc/type` in the doctree entry map
    - The contents of a file will be stored at the returned value

  See [[process-toc-entry]] for the specifics.

  NOTE: I find a multimethod not perfectly appropriate here but it's straightforward to extend
  from other artifacts and - for now - gets the job done."
  (fn [path-str]
    (second (re-find #"\.([^\.]+)$" path-str))))

(defmethod filepath->type "markdown" [_] :cljdoc/markdown)
(defmethod filepath->type "md" [_] :cljdoc/markdown)
(defmethod filepath->type "adoc" [_] :cljdoc/asciidoc)

(defn- process-toc-entry
  [{:keys [slurp-fn get-contributors] :as fns}
   {:keys [title attrs children]}]
  {:pre [(string? title) (fn? slurp-fn) (fn? get-contributors)]}
  ;; If there is a file it has to be matched by filepath->type's dispatch-fn
  ;; Otherwise the line below will throw an exception (intentionally so)
  (let [entry-type (some-> attrs :file filepath->type)
        file (-> attrs :file)
        slurp! (fn [file] (or (slurp-fn file)
                              (throw (Exception. (format "Could not read contents of %s" file)))))]
    (cond-> {:title title}

      (:file attrs)
      (assoc-in [:attrs :cljdoc.doc/source-file] (:file attrs))

      entry-type
      (assoc-in [:attrs entry-type] (slurp! (:file attrs)))

      entry-type
      (assoc-in [:attrs :cljdoc.doc/type] entry-type)

      (nil? (:slug attrs))
      (assoc-in [:attrs :slug] (cuerdas/uslug title))

      (:file attrs)
      (assoc-in [:attrs :cljdoc.doc/contributors] (get-contributors file))

      (seq children)
      (assoc :children (mapv (partial process-toc-entry fns) children)))))

(spec/def ::slurp-fn fn?)
(spec/def ::get-contributors fn?)

(spec/def ::process-fns
  (spec/keys :req-un [::slurp-fn ::get-contributors]))

(spec/fdef process-toc
  :args (spec/cat :process-fns ::process-fns
                  :entries (spec/coll-of ::hiccup-entry))
  :ret ::doctree)

(defn process-toc
  "Process a doctree `toc` and inline all file contents via `slurp-fn`.

  The `toc` should be a collection of `::hiccup-entry`s.
  The return value will be a `::doctree`. Individual nodes will have
  an `:attrs` key with additional metadata such as the original file
  name, the file contents and a slug derived from the entry title."
  [process-fns toc]
  (->> toc
       (spec/conform (spec/coll-of ::hiccup-entry))
       (mapv (partial process-toc-entry process-fns))))

(spec/def ::link-children
  (spec/coll-of ::link-entry))
(spec/def ::link-attrs
  (spec/keys :req [:cljdoc.doc/external-url]))
(spec/def ::link-entry
  (spec/keys :req-un [::title]
             :opt-un [::link-attrs ::link-children]))
(spec/def ::links
  (spec/coll-of ::link-entry))

(defn process-link-entry [{:keys [title attrs children]}]
  (cond-> {:title title}

    (:url attrs)
    (assoc-in [:link-attrs :cljdoc.doc/external-url] (:url attrs))

    (seq children)
    (assoc :link-children (mapv process-link-entry children))))

(spec/def ::hiccup-link-attrs
  (spec/keys :req-un [::url]))
(spec/def ::hiccup-link-entry
  (spec/spec
   (spec/cat :title ::title
             :attrs (spec/? ::hiccup-link-attrs)
             :children (spec/* ::hiccup-link-entry))))
(spec/def ::hiccup-link-entries (spec/coll-of ::hiccup-link-entry))

(spec/fdef process-links
  :args (spec/cat :links ::hiccup-link-entries)
  :ret ::links)

(defn process-links [links]
  (->> links
       (spec/conform ::hiccup-link-entries)
       (mapv process-link-entry)))

(defn entry->type-and-content
  "Given a single doctree entry return a tuple with the type of the to be rendered document and
  it's content. This is a layer of indirection to enable backwards compatibility with doctrees
  that do not contain a :cljdoc.doc/type key."
  [doctree-entry]
  (if-let [t (:cljdoc.doc/type doctree-entry)]
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

(defn get-neighbour-entries
  "Return list with 3 entry of doctree, where second is entry that matched 
   by slug-path. First entry is previous entry on the same level of the tree,
   or it is last children of previous entry in tree. Third entry it is next to
   the matched, or next entry to the parent entry. If there is no previous 
   or next entry to the matched, it will have nil instead."
  [doc-tree doc-slug-path]
  (let [neighbour-articles (partition 3 1 (concat [nil] (flatten* doc-tree) [nil]))]
    (->> neighbour-articles
         (filter #(-> %
                      second
                      :attrs
                      :slug-path
                      (= doc-slug-path)))
         first)))

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

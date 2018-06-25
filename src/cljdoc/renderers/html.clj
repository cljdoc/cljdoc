(ns cljdoc.renderers.html
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.api :as api]
            [cljdoc.util :as util]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.cache]
            [cljdoc.spec]
            [cljdoc.server.routes :as routes]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(defn render-to [opts hiccup ^java.io.File file]
  (log/info "Writing" (clojure.string/replace (.getPath file) #"^.+grimoire-html" "grimoire-html"))
  (->> hiccup (layout/page opts) str (spit file)))

(defn file-for [out-dir route-id route-params]
  (doto (io/file out-dir (subs (routes/url-for route-id :path-params route-params) 1) "index.html")
    io/make-parents))

(defmulti render (fn [page-type route-params cache-bundle] page-type))

(defmethod render :default
  [page-type _ _]
  (format "%s not implemented, sorry" page-type))

(defmethod render :group/index
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (let [group-id (:group-id route-params)
        big-btn-link :a.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div.pa4-ns.pa2
          [:h1 group-id]
          [:span.db "Known artifacts and versions under the group " group-id]
          (for [a (:artifacts cache-contents)]
            [:div
             [:h3 (format "%s/%s" group-id a)]
             [:ol.list.pl0.pv3
              (for [version (->> (:versions cache-contents)
                                 (filter #(= (:artifact-id %) a))
                                 (sort-by :version)
                                 (reverse))]
                [:li.dib.mr3
                 [big-btn-link
                  {:href (routes/url-for :artifact/version :path-params (assoc version :group-id group-id))}
                  (:version version)]])]])]
         (layout/page {:title (str group-id " — cljdoc")}))))

(defmethod render :artifact/index
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (let [artifact-id (:artifact-id route-params)
        artifact-entity (assoc cache-id :artifact-id artifact-id)
        big-btn-link :a.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div.pa4-ns.pa2
          [:h1 (util/clojars-id artifact-entity)]
          [:span.db "Known versions on cljdoc:"]
          [:ol.list.pl0.pv3
           (for [v (->> (:versions cache-contents)
                        (filter #(= (:artifact-id %) (:artifact-id route-params)))
                        (sort-by :version)
                        (reverse))]
             [:li.dib.mr3
              [big-btn-link
               {:href (routes/url-for :artifact/version :path-params (merge cache-id v))}
               (:version v)]])]
          (when-not (= #{artifact-id} (set(:artifacts cache-contents)))
            [:div
             [:h3 "Other artifacts under the " (:group-id cache-id) " group"]
             [:ol.list.pl0.pv3
              (for [a (sort (:artifacts cache-contents))]
                [:li.dib.mr3
                 [big-btn-link
                  {:href (routes/url-for :artifact/index :path-params (assoc cache-id :artifact-id a))}
                  a]])]])]
         (layout/page {:title (str (util/clojars-id artifact-entity) " — cljdoc")}))))

(defmethod render :artifact/version
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (->> [:div
        (layout/top-bar cache-id (-> cache-contents :version :scm :url))
        (layout/sidebar
         (articles/article-list
          (articles/doc-tree-view cache-id (doctree/add-slug-path (-> cache-contents :version :doc)) []))
         (api/namespace-list {} (cljdoc.cache/namespaces cache-bundle)))]
       (layout/page {:title (str (util/clojars-id cache-id) " " (:version cache-id))})))

(defmethod render :artifact/doc
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:doc-slug-path route-params))
  (let [doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> cache-contents :version :doc))
        doc-p (->> doc-tree
                   doctree/flatten*
                   (filter #(= doc-slug-path (:slug-path (:attrs %))))
                   first)
        doc-html (or (some-> doc-p :attrs :cljdoc/markdown rich-text/markdown-to-html)
                     (some-> doc-p :attrs :cljdoc/asciidoc rich-text/asciidoc-to-html))]
    (->> (if doc-html
           (articles/doc-page {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                               :doc-tree-component (articles/doc-tree-view cache-id doc-tree doc-slug-path)
                               :namespace-list-component (api/namespace-list {} (cljdoc.cache/namespaces cache-bundle))
                               :doc-scm-url (str (-> cache-contents :version :scm :url) "/blob/master/"
                                                 (-> doc-p :attrs :cljdoc.doc/source-file))
                               :doc-html (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                                                     doc-html
                                                     {:scm (:scm (:version cache-contents))
                                                      :uri-map (fixref/uri-mapping cache-id (doctree/flatten* doc-tree))})})
           (articles/doc-overview {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                                   :doc-tree-component (articles/doc-tree-view cache-id doc-tree doc-slug-path)
                                   :namespace-list-component (api/namespace-list {} (cljdoc.cache/namespaces cache-bundle))
                                   :cache-id cache-id
                                   :doc-tree (doctree/get-subtree doc-tree doc-slug-path)}))
         (layout/page {:title (str (:title doc-p) " — " (util/clojars-id cache-id) " " (:version cache-id))}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:namespace route-params))
  (let [ns-emap route-params
        defs (filter #(= (:namespace ns-emap) (:namespace %)) (:defs cache-contents))
        ns-data (first (filter #(= (:namespace ns-emap) (:name %)) ;PLATF_SUPPORT
                               (:namespaces cache-contents)))
        common-params {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                       :article-list-component (articles/article-list
                                                (articles/doc-tree-view cache-id
                                                                        (doctree/add-slug-path (-> cache-contents :version :doc))
                                                                        []))
                       :namespace-list-component (api/namespace-list
                                                  {:current (:namespace ns-emap)}
                                                  (cljdoc.cache/namespaces cache-bundle))}]
    (when (empty? defs)
      (log/warnf "Namespace %s contains no defs" (:namespace route-params)))
    (->> (if ns-data
           (api/namespace-page (merge common-params
                                      {:scm-info (:scm (:version cache-contents))
                                       :ns-entity ns-emap
                                       :ns-data ns-data
                                       :defs defs}))
           (api/sub-namespace-overview-page (merge common-params
                                                   {:ns-entity ns-emap
                                                    :namespaces (:namespaces cache-contents)
                                                    :defs (:defs cache-contents)})))
         (layout/page {:title (str (:namespace ns-emap) " — " (util/clojars-id cache-id) " " (:version cache-id))}))))

(defn write-docs* [{:keys [cache-contents cache-id] :as cache-bundle} ^java.io.File out-dir]
  (cljdoc.spec/assert :cljdoc.spec/cache-bundle cache-bundle)
  (let [top-bar-comp (layout/top-bar cache-id (-> cache-contents :version :scm-url))
        doc-tree     (doctree/add-slug-path (-> cache-contents :version :doc))]

    ;; Index page for given version
    (log/info "Rendering index page for" cache-id)
    (->> (str (render :artifact/version {} cache-bundle))
         (spit (file-for out-dir :artifact/version cache-id)))

    ;; Documentation Pages / Articles
    (doseq [doc-p (-> doc-tree doctree/flatten*)
            :when (:cljdoc/source-file doc-p)]
      (log/info "Rendering Doc Page" (dissoc doc-p :attrs))
      (->> (str (render :artifact/doc {:doc-slug-path (:slug-path (:attrs doc-p))} cache-bundle))
           (spit (->> (-> doc-p :attrs :slug-path)
                      (clojure.string/join "/")
                      (assoc cache-id :article-slug)
                      (file-for out-dir :artifact/doc)))))

    ;; Namespace Pages
    (doseq [ns-emap (cljdoc.cache/namespaces cache-bundle)
            :let [defs (filter #(= (:namespace ns-emap)
                                   (:namespace %))
                               (:defs cache-contents))]]
      (log/infof "Rendering namespace %s" (:namespace ns-emap))
      (->> (str (render :artifact/namespace ns-emap cache-bundle))
           (spit (file-for out-dir :artifact/namespace ns-emap))))))

(comment


  (write-docs store platf out)

  (defn namespace-hierarchy [ns-list]
    (reduce (fn [hierarchy ns-string]
              (let [ns-path (clojure.string/split ns-string #"\.")]
                (if (get-in hierarchy ns-path)
                  hierarchy
                  (assoc-in hierarchy ns-path nil))))
            {}
            ns-list))

  (namespace-hierarchy (map :name namespaces))

  (-> (doctree/add-slug-path (-> (:cache-contents cljdoc.cache/cache) :version :doc))
      first)

  )

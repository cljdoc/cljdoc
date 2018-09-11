(ns cljdoc.renderers.html
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.api :as api]
            [cljdoc.util :as util]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.bundle :as bundle]
            [cljdoc.platforms :as platf]
            [cljdoc.spec]
            [cljdoc.server.routes :as routes]
            [version-clj.core :as v]
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
        big-btn-link :a.db.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
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
                [:li.dib.mr3.mb3
                 [big-btn-link
                  {:href (routes/url-for :artifact/version :path-params (assoc version :group-id group-id))}
                  (:version version)]])]])]
         (layout/page {:title (str group-id " — cljdoc")
                       :description (format "All artifacts under the group-id %s for which there is documenation on cljdoc"
                                            (:group-id cache-id))}))))

(defmethod render :artifact/index
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (let [artifact-id (:artifact-id route-params)
        artifact-entity (assoc cache-id :artifact-id artifact-id)
        big-btn-link :a.db.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div.pa4-ns.pa2
          [:h1 (util/clojars-id artifact-entity)]
          [:span.db "Known versions on cljdoc:"]
          [:ol.list.pl0.pv3
           (for [v (->> (:versions cache-contents)
                        (filter #(= (:artifact-id %) (:artifact-id route-params)))
                        (sort-by :version v/version-compare)
                        (reverse))]
             [:li.dib.mr3.mb3
              [big-btn-link
               {:href (routes/url-for :artifact/version :path-params (merge cache-id v))}
               (:version v)]])]
          (when-not (= #{artifact-id} (set(:artifacts cache-contents)))
            [:div
             [:h3 "Other artifacts under the " (:group-id cache-id) " group"]
             [:ol.list.pl0.pv3
              (for [a (sort (:artifacts cache-contents))]
                [:li.dib.mr3.mb3
                 [big-btn-link
                  {:href (routes/url-for :artifact/index :path-params (assoc cache-id :artifact-id a))}
                  a]])]])]
         (layout/page {:title (str (util/clojars-id artifact-entity) " — cljdoc")
                       :description (layout/description cache-id)}))))

(defmethod render :artifact/version
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (->> [:div
        (layout/top-bar cache-id (-> cache-contents :version :scm :url))
        (layout/sidebar
         (articles/article-list
          (articles/doc-tree-view cache-id (doctree/add-slug-path (-> cache-contents :version :doc)) []))
         (api/namespace-list {} (bundle/ns-entities cache-bundle)))]
       (layout/page {:title (str (util/clojars-id cache-id) " " (:version cache-id))
                     :description (layout/description cache-id)})))

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
                               :namespace-list-component (api/namespace-list {} (bundle/ns-entities cache-bundle))
                               :doc-scm-url (str (-> cache-contents :version :scm :url) "/blob/master/"
                                                 (-> doc-p :attrs :cljdoc.doc/source-file))
                               :doc-html (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                                                     doc-html
                                                     {:scm (:scm (:version cache-contents))
                                                      :uri-map (fixref/uri-mapping cache-id (doctree/flatten* doc-tree))})})
           (articles/doc-overview {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                                   :doc-tree-component (articles/doc-tree-view cache-id doc-tree doc-slug-path)
                                   :namespace-list-component (api/namespace-list {} (bundle/ns-entities cache-bundle))
                                   :cache-id cache-id
                                   :doc-tree (doctree/get-subtree doc-tree doc-slug-path)}))
         (layout/page {:title (str (:title doc-p) " — " (util/clojars-id cache-id) " " (:version cache-id))
                       :description (layout/description cache-id)}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:namespace route-params))
  (let [ns-emap route-params
        defs    (bundle/defs-for-ns (:defs cache-contents) (:namespace ns-emap))
        ns-data (first (filter #(= (:namespace ns-emap) (platf/get-field % :name))
                               (bundle/namespaces cache-bundle)))
        common-params {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                       :article-list-component (articles/article-list
                                                (articles/doc-tree-view cache-id
                                                                        (doctree/add-slug-path (-> cache-contents :version :doc))
                                                                        []))
                       :namespace-list-component (api/namespace-list
                                                  {:current (:namespace ns-emap)}
                                                  (bundle/ns-entities cache-bundle))}]
    (->> (if ns-data
           (api/namespace-page (merge common-params
                                      {:scm-info (:scm (:version cache-contents))
                                       :ns-entity ns-emap
                                       :ns-data ns-data
                                       :defs defs}))
           (api/sub-namespace-overview-page (merge common-params
                                                   {:ns-entity ns-emap
                                                    :namespaces (bundle/namespaces cache-bundle)
                                                    :defs (:defs cache-contents)})))
         (layout/page {:title (str (:namespace ns-emap) " — " (util/clojars-id cache-id) " " (:version cache-id))
                       :description (layout/description cache-id)}))))

(comment

  (defn namespace-hierarchy [ns-list]
    (reduce (fn [hierarchy ns-string]
              (let [ns-path (clojure.string/split ns-string #"\.")]
                (if (get-in hierarchy ns-path)
                  hierarchy
                  (assoc-in hierarchy ns-path nil))))
            {}
            ns-list))

  (namespace-hierarchy (map :name namespaces))

  (-> (doctree/add-slug-path (-> (:cache-contents cljdoc.bundle/cache) :version :doc))
      first)

  )

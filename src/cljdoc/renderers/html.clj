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

(defmulti render (fn [page-type route-params cache-bundle] page-type))

(defmethod render :default
  [page-type _ _]
  (format "%s not implemented, sorry" page-type))

(defmethod render :artifact/version
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (->> (layout/layout
        ;; TODO on mobile this will effectively be rendered as a blank page
        ;; We could instead show a message and the namespace tree.
        {:top-bar (layout/top-bar cache-id (-> cache-contents :version :scm :url))
         :main-sidebar-contents [(articles/article-list
                                  (articles/doc-tree-view cache-id
                                                          (doctree/add-slug-path (-> cache-contents :version :doc))
                                                          []
                                                          true))
                                 (api/namespace-list {} (bundle/ns-entities cache-bundle))]})
       (layout/page {:title (str (util/clojars-id cache-id) " " (:version cache-id))
                     :description (layout/description cache-id)})))

(defmethod render :artifact/doc
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:doc-slug-path route-params))
  (let [doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> cache-contents :version :doc))
        [doc-tree-with-readme-and-changelog doc-tree-with-rest] ((juxt filter remove) (fn is-readme-or-changelog [entry]
                                                                                        (contains? #{"Readme" "Changelog"} (:title entry)))
                                                                 doc-tree)
        doc-p (->> doc-tree
                   doctree/flatten*
                   (filter #(= doc-slug-path (:slug-path (:attrs %))))
                   first)
        doc-html (or (some-> doc-p :attrs :cljdoc/markdown rich-text/markdown-to-html)
                     (some-> doc-p :attrs :cljdoc/asciidoc rich-text/asciidoc-to-html))
        common {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
                :main-list-component (articles/doc-tree-view cache-id doc-tree-with-readme-and-changelog doc-slug-path false)
                :article-list-component (articles/doc-tree-view cache-id doc-tree-with-rest doc-slug-path true)
                :namespace-list-component (api/namespace-list {} (bundle/ns-entities cache-bundle))
                :upgrade-notice-component (if-let [newer-v (bundle/more-recent-version cache-bundle)]
                                            (layout/upgrade-notice newer-v))}]
    (->> (if doc-html
           (articles/doc-page
            (merge common
                   {:doc-scm-url (str (-> cache-contents :version :scm :url) "/blob/master/"
                                      (-> doc-p :attrs :cljdoc.doc/source-file))
                    :doc-html (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                                          doc-html
                                          {:scm (:scm (:version cache-contents))
                                           :uri-map (fixref/uri-mapping cache-id (doctree/flatten* doc-tree))})}))
           (articles/doc-overview
            (merge common
                   {:cache-id cache-id
                    :doc-tree (doctree/get-subtree doc-tree doc-slug-path)})))
         (layout/page {:title (str (:title doc-p) " — " (util/clojars-id cache-id) " " (:version cache-id))
                       :canonical-url (some->> (bundle/more-recent-version cache-bundle)
                                               (merge route-params)
                                               (routes/url-for :artifact/doc :path-params))
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
                                                                        []
                                                                        true))
                       :namespace-list-component (api/namespace-list
                                                  {:current (:namespace ns-emap)}
                                                  (bundle/ns-entities cache-bundle))
                       :upgrade-notice-component (if-let [newer-v (bundle/more-recent-version cache-bundle)]
                                                   (layout/upgrade-notice newer-v))}]
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
                       :canonical-url (some->> (bundle/more-recent-version cache-bundle)
                                               (merge route-params)
                                               (routes/url-for :artifact/namespace :path-params))
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

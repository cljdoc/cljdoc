(ns cljdoc.render
  (:require [cljdoc-shared.proj :as proj]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.docset :as docset]
            [cljdoc.platforms :as platf]
            [cljdoc.render.api :as api]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.sidebar :as sidebar]
            [cljdoc.server.routes :as routes]
            [cljdoc.user-config :as user-config]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.util.scm :as scm]))

(defmulti render (fn [page-type _route-params _docset] page-type))

(defmethod render :default
  [page-type _ _]
  (format "%s not implemented, sorry" page-type))

(defmethod render :artifact/version
  [_ route-params {:keys [docset pom last-build static-resources]}]
  (let [version-entity (:version-entity docset)]
    (->> (layout/layout
          ;; TODO on mobile this will effectively be rendered as a blank page
          ;; We could instead show a message and the namespace tree.
          {:top-bar (layout/top-bar {:version-entity version-entity
                                     :scm-url (-> docset :version :scm :url)
                                     :static-resources static-resources})
           :main-sidebar-contents (sidebar/sidebar-contents route-params docset last-build)})
         (layout/page {:title (str (proj/clojars-id version-entity) " " (:version version-entity))
                       :og-img-data {:id "4j9ovv5ojagy8ik"
                                     :page-title (:description pom)
                                     :project-name (proj/clojars-id version-entity)}
                       :description (layout/artifact-description version-entity (:description pom))
                       :static-resources static-resources}))))

(defmethod render :artifact/doc
  [_ route-params {:keys [docset pom last-build static-resources]}]
  (assert (:doc-slug-path route-params))
  (let [version-entity (:version-entity docset)
        doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> docset :version :doc))
        doc-p (->> doc-tree
                   doctree/flatten*
                   (filter #(= doc-slug-path (:slug-path (:attrs %))))
                   first)
        [doc-type contents] (doctree/entry->type-and-content doc-p)
        top-bar-component (layout/top-bar {:version-entity version-entity
                                           :scm-url (-> docset :version :scm :url)
                                           :static-resources static-resources})
        sidebar-contents (sidebar/sidebar-contents route-params docset last-build)
        articles-block (doctree/get-neighbour-entries (remove #(contains? #{"Readme" "Changelog"}
                                                                          (:title %))
                                                              doc-tree)
                                                      doc-slug-path)
        default-doc? (= (first (:doc-slug-path route-params))
                        (-> docset :version :doc first :attrs :slug))
        prev-page (first articles-block)
        next-page (last articles-block)]
    ;; If we can find an article for the provided `doc-slug-path` render that article,
    ;; if there's no article then the page should display a list of all child-pages
    (->> (if doc-type
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents sidebar-contents
             :content (articles/doc-page
                       {:doc-scm-url (scm/branch-url (docset/scm-info docset)
                                                     (-> doc-p :attrs :cljdoc.doc/source-file))
                        :contributors (-> doc-p :attrs :cljdoc.doc/contributors)
                        :doc-type (name doc-type)
                        :doc-html (fixref/fix (rich-text/render-text [doc-type contents])
                                              {:scm-file-path (-> doc-p :attrs :cljdoc.doc/source-file)
                                               :scm (docset/articles-scm-info docset)
                                               :uri-map (fixref/uri-mapping version-entity (doctree/flatten* doc-tree))})
                        :version-entity version-entity
                        :prev-page prev-page
                        :next-page next-page})})
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents sidebar-contents
             :content (articles/doc-overview
                       {:version-entity version-entity
                        :doc-tree (doctree/get-subtree doc-tree doc-slug-path)
                        :prev-page prev-page
                        :next-page next-page})}))

         (layout/page {:title (str (:title doc-p) " — " (proj/clojars-id version-entity) " " (:version version-entity))
                       :canonical-url (some->> (docset/more-recent-version docset)
                                               (merge route-params)
                                               (routes/url-for :artifact/doc :path-params))
                       :og-img-data {:id "4j9ovv5ojagy8ik"
                                     :page-title (if default-doc?
                                                   (:description pom)
                                                   (:title doc-p))
                                     :project-name (proj/clojars-id version-entity)}
                       :description (layout/artifact-description version-entity (:description pom))
                       :page-features (when doc-type (rich-text/determine-features [doc-type contents]))
                       :static-resources static-resources}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [docset pom last-build static-resources]}]
  (assert (:namespace route-params))
  (let [version-entity (:version-entity docset)
        ns-emap route-params
        valid-ref-pred (api/valid-ref-pred-fn docset)
        ns-defs    (docset/defs-for-ns-with-src-uri docset (:namespace ns-emap))
        [[dominant-platf] :as platf-stats] (api/platform-stats ns-defs)
        ns-data (docset/get-namespace docset (:namespace ns-emap))
        top-bar-component (layout/top-bar {:version-entity version-entity
                                           :scm-url (docset/scm-url docset)
                                           :static-resources static-resources})
        opts {:docstring-format (user-config/docstring-format
                                 (-> docset :version :config)
                                 (proj/clojars-id version-entity))
              :scm (docset/scm-info docset)
              :uri-map (fixref/uri-mapping version-entity
                                           (-> docset
                                               :version
                                               :doc
                                               doctree/add-slug-path
                                               doctree/flatten*))}]
    (->> (if ns-data
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents (sidebar/sidebar-contents route-params docset last-build)
             :vars-sidebar-contents (when (seq ns-defs)
                                      [(api/platforms-supported-note platf-stats)
                                       (api/definitions-list ns-defs {:indicate-platforms-other-than dominant-platf})])
             :content (api/namespace-page {:ns-entity ns-emap
                                           :ns-data ns-data
                                           :defs ns-defs
                                           :valid-ref-pred valid-ref-pred
                                           :opts opts})})
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents (sidebar/sidebar-contents route-params docset last-build)
             :content (api/sub-namespace-overview-page {:ns-entity ns-emap
                                                        :namespaces (docset/namespaces docset)
                                                        :defs (docset/all-defs docset)
                                                        :valid-ref-pred valid-ref-pred
                                                        :opts opts})}))
         (layout/page {:title (str (:namespace ns-emap) " — " (proj/clojars-id version-entity) " " (:version version-entity))
                       :og-img-data {:id "4j9ovv5ojagy8ik"
                                     :page-title (str "(ns " (:namespace ns-emap) ")")
                                     :subtitle (when ns-data (platf/get-field ns-data :doc))
                                     :project-name (proj/clojars-id version-entity)}

                       :canonical-url (some->> (docset/more-recent-version docset)
                                               (merge route-params)
                                               (routes/url-for :artifact/namespace :path-params))
                       :description (layout/artifact-description version-entity (:description pom))
                       :static-resources static-resources}))))

(ns cljdoc.renderers.html
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.sidebar :as sidebar]
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
            [clojure.java.io :as io])
  (:import (org.jsoup Jsoup)))

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
         :main-sidebar-contents (sidebar/sidebar-contents route-params cache-bundle)})
       (layout/page {:title (str (util/clojars-id cache-id) " " (:version cache-id))
                     :description (layout/artifact-description
                                   cache-id
                                   (-> (Jsoup/parse (-> cache-contents :version :pom))
                                       (.select "description")
                                       (.text)))})))

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
                     (some-> doc-p :attrs :cljdoc/asciidoc rich-text/asciidoc-to-html))
        top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
        sidebar-contents (sidebar/sidebar-contents route-params cache-bundle)]
    ;; If we can find an article for the provided `doc-slug-path` render that article,
    ;; if there's no article then the page should display a list of all child-pages
    (->> (if doc-html
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents sidebar-contents
             :content (articles/doc-page
                       {:doc-scm-url (str (-> cache-contents :version :scm :url) "/blob/master/"
                                          (-> doc-p :attrs :cljdoc.doc/source-file))
                        :doc-html (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                                              doc-html
                                              {:scm (:scm (:version cache-contents))
                                               :uri-map (fixref/uri-mapping cache-id (doctree/flatten* doc-tree))})})})
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents sidebar-contents
             :content (articles/doc-overview
                       {:cache-id cache-id
                        :doc-tree (doctree/get-subtree doc-tree doc-slug-path)})}))

         (layout/page {:title (str (:title doc-p) " — " (util/clojars-id cache-id) " " (:version cache-id))
                       :canonical-url (some->> (bundle/more-recent-version cache-bundle)
                                               (merge route-params)
                                               (routes/url-for :artifact/doc :path-params))
                       ;; update desctiption by extracting it from XML (:pom cache-bundle)
                       :description (layout/artifact-description
                                     cache-id
                                     (-> (Jsoup/parse (-> cache-contents :version :pom))
                                         (.select "description")
                                         (.text)))}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:namespace route-params))
  (let [ns-emap route-params
        defs    (bundle/defs-for-ns (:defs cache-contents) (:namespace ns-emap))
        [[dominant-platf] :as platf-stats] (api/platform-stats defs)
        ns-data (first (filter #(= (:namespace ns-emap) (platf/get-field % :name))
                               (bundle/namespaces cache-bundle)))
        top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))
        common-params {:top-bar-component (layout/top-bar cache-id (-> cache-contents :version :scm :url))}]
    (->> (if ns-data
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents (sidebar/sidebar-contents route-params cache-bundle)
             :vars-sidebar-contents [(api/platform-support-note platf-stats)
                                     (api/definitions-list ns-emap defs {:indicate-platforms-other-than dominant-platf})]
             :content (api/namespace-page {:scm-info (:scm (:version cache-contents))
                                           :ns-entity ns-emap
                                           :ns-data ns-data
                                           :defs defs})})
           (layout/layout
            {:top-bar top-bar-component
             :main-sidebar-contents (sidebar/sidebar-contents route-params cache-bundle)
             :content (api/sub-namespace-overview-page {:ns-entity ns-emap
                                                        :namespaces (bundle/namespaces cache-bundle)
                                                        :defs (:defs cache-contents)})}))
         (layout/page {:title (str (:namespace ns-emap) " — " (util/clojars-id cache-id) " " (:version cache-id))
                       :canonical-url (some->> (bundle/more-recent-version cache-bundle)
                                               (merge route-params)
                                               (routes/url-for :artifact/namespace :path-params))
                       :description (layout/artifact-description
                                     cache-id
                                     (-> (Jsoup/parse (-> cache-contents :version :pom))
                                         (.select "description")
                                         (.text)))}))))

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

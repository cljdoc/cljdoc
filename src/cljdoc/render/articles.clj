(ns cljdoc.render.articles
  "HTML fragments related to rendering articles and article-trees"
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.util :as util]
            [cljdoc.routes :as routes]
            [io.pedestal.http.route :as route]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]))

(defn article-list [doc-tree]
  [:div
   (layout/sidebar-title "Articles")
   (or doc-tree
       [:p.pl2.f7.gray
        [:a.blue.link {:href (util/github-url :userguide/articles)} "Articles"]
        " are a practical way to provide additional guidance beyond
       API documentation. To use them, please ensure you "
        [:a.blue.link {:href (util/github-url :userguide/scm-faq)} "properly set SCM info"]
        " in your project."])])

(defn doc-link [cache-id slugs]
  (assert (seq slugs) "Slug path missing")
  (->> (string/join "/" slugs)
       (assoc cache-id :article-slug)
       (route/url-for :artifact/doc :path-params)))

(defn doc-tree-view
  [cache-id doc-bundle current-page]
  (when (seq doc-bundle)
    (->> doc-bundle
         (map (fn [doc-page]
                (let [slug-path (-> doc-page :attrs :slug-path)]
                    [:li
                     (if (some-> doc-page :attrs :cljdoc.doc/source-file)
                       [:a.link.blue.hover-dark-blue.dib.pa1
                        {:href  (doc-link cache-id slug-path)
                         :class (when (= current-page slug-path) "fw7")}
                        (:title doc-page)]
                       [:span.blue.pa1.dib (:title doc-page)])
                     (doc-tree-view cache-id (:children doc-page) current-page)])))
         (into [:ul.list.pl2]))))

(defn doc-page [{:keys [top-bar-component
                        doc-tree-component
                        namespace-list-component
                        doc-scm-url
                        doc-html] :as args}]
  [:div
   top-bar-component
   (layout/sidebar
    (article-list doc-tree-component)
    namespace-list-component)
   (layout/main-container
    {:offset "16rem"}
    [:div.mw7.center
     ;; TODO dispatch on a type parameter that becomes part of the attrs map
     (if doc-html
       [:div.markdown.lh-copy.pv4
        (hiccup/raw doc-html)
        [:a.db.f7.tr {:href doc-scm-url} "Edit on GitHub"]]
       [:div.lh-copy.pv6.tc
        #_[:pre (pr-str (dissoc args :top-bar-component :doc-tree-component :namespace-list-component))]
        [:span.f4.serif.gray.i "Space intentionally left blank."]])])])

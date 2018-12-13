(ns cljdoc.render.articles
  "HTML fragments related to rendering articles and article-trees"
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.util :as util]
            [cljdoc.util.scm :as scm]
            [cljdoc.server.routes :as routes]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]))

(defn doc-link [cache-id slugs]
  (assert (seq slugs) "Slug path missing")
  (->> (string/join "/" slugs)
       (assoc cache-id :article-slug)
       (routes/url-for :artifact/doc :path-params)))

(defn doc-tree-view
  "Render a set of nested lists representing the doctree. "
  ([cache-id doc-bundle current-page]
   (doc-tree-view cache-id doc-bundle current-page 0))
  ([cache-id doc-bundle current-page level]
   (when (seq doc-bundle)
     (->> doc-bundle
          (map (fn [doc-page]
                 (let [slug-path (-> doc-page :attrs :slug-path)]
                   [:li
                    {:class (when (seq (:children doc-page)) "mv2")}
                    [:a.link.blue.hover-dark-blue.dib.pv1
                     {:style {:word-wrap "break-word"}
                      :href  (doc-link cache-id slug-path)
                      :class (when (= current-page slug-path) "fw7")}
                     (:title doc-page)]
                    (doc-tree-view cache-id (:children doc-page) current-page (inc level))])))
          (into [:ul.list.ma0 {:class (if (pos? level) "f6-ns pl2" "pl0")}])))))

(defn doc-page [{:keys [doc-scm-url doc-html]}]
  [:div.mw7.center
   ;; TODO dispatch on a type parameter that becomes part of the attrs map
   (if doc-html
     [:div#doc-html.markdown.lh-copy.pv4
      (hiccup/raw doc-html)
      [:a.db.f7.tr
       {:href doc-scm-url}
       (if (= :gitlab (scm/provider doc-scm-url))
         "Edit on GitLab"
         "Edit on GitHub")]]
     [:div.lh-copy.pv6.tc
      [:span.f4.serif.gray.i "Space intentionally left blank."]])])

(defn doc-overview
  [{:keys [cache-id doc-tree]}]
  [:div.doc-page
   [:div.mw7.center.pv4
    [:h1 (:title doc-tree)]
    [:ol
     (for [c (:children doc-tree)]
       [:li.mv2
        [:a.link.blue
         {:href (doc-link cache-id (-> c :attrs :slug-path))}
         (-> c :title)]])]]])

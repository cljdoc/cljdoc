(ns cljdoc.render.sidebar
  (:require [cljdoc.doc-tree :as doctree]
            [cljdoc.server.routes :as routes]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.links :as links]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.searchset-search :as searchset-search]
            [cljdoc.render.api :as api]
            [cljdoc.bundle :as bundle]))

(defn current-release-notice [{:keys [version] :as version-map}]
  [:a.db.link.bg-washed-yellow.pa2.f7.mb3.dark-gray.lh-title
   {:href (routes/url-for :artifact/version :path-params version-map)}
   "Current release is " [:span.blue version]])

(defn last-build-warning
  "If the provided build had problems, render a warning and link to the respective build."
  [build]
  (assert build)
  (let [render-error (fn render-buold-warning [msg]
                       [:a.db.mb3.pa2.bg-washed-red.br2.f7.red.b.lh-copy.link
                        {:href (str "/builds/" (:id build))}
                        msg " "
                        [:span.underline.nowrap  "build #" (:id build)]])]
    (cond
      (and (not (build-log/api-import-successful? build))
           (not (build-log/git-import-successful? build)))
      (render-error "API & Git import failed in")

      (not (build-log/api-import-successful? build))
      (render-error "API import failed in")

      (not (build-log/git-import-successful? build))
      (render-error "Git import failed in"))))

(defn sidebar-contents
  "Render a sidebar for a documentation page.

  This function takes the same arguments as the main render functions in `cljdoc.render`
  and selected pages/namespaces will be highlighted based on the supplied `route-params`.

  If articles or namespaces are missing for a project there will be little messages pointing
  users to the relevant documentation or GitHub to open an issue."
  [route-params {:keys [version-entity] :as cache-bundle} last-build]
  (let [doc-tree (doctree/add-slug-path (-> cache-bundle :version :doc))
        split-doc-tree ((juxt filter remove)
                        #(contains? #{"Readme" "Changelog"} (:title %))
                        doc-tree)
        readme-and-changelog (first split-doc-tree)
        doc-tree-with-rest (second split-doc-tree)
        no-articles? (not (seq doc-tree))
        no-cljdoc-config? (not (some-> cache-bundle :version :config))
        no-scm? (not (some-> cache-bundle :version :scm))
        articles-tip? (or no-articles? no-cljdoc-config? no-scm?)]
    [;; Upgrade notice
     (when-let [newer-v (bundle/more-recent-version cache-bundle)]
       (current-release-notice newer-v))

     (when last-build
       (last-build-warning last-build))

     [:div.mb4
      (searchset-search/searchbar version-entity)]

     [:div.mb4.js--articles
      (layout/sidebar-title [:span "Articles"
                             (when articles-tip?
                               [:sup.ttl
                                [:a#js--articles-tip-toggler {:href "#"}
                                 [:span.link.dib.ml1.v-mid.hover-blue.ttl.silver "tip"]]])])
      (when articles-tip?
        [:div#js--articles-tip.dn
         [:div.mb1.ba.br3.silver.b--blue.bw1.db.f6.w-100.pt1.pa1
          (cond
            no-scm?
            (list [:p.ma1 "No Git repository found for library."]
                  [:p.ma1 [:a.blue.link {:href (links/github-url :userguide/scm-faq)} "→ Git Sources docs"]])
            no-articles?
            (list [:p.ma1 "No articles found in Git repository."]
                  [:p.ma1 [:a.blue.link {:href (links/github-url :userguide/articles)} "→ Article docs"]])
            no-cljdoc-config?
            (list [:p.ma1 "No cljdoc config found, articles auto discovered."]
                  [:p.ma1 "Library authors can specify article order and hierarchy."]
                  [:p.ma1 [:a.blue.link {:href (links/github-url :userguide/articles)} "→ Articles docs"]]))]])

      (if no-articles?
        [:div.mb4
         [:p.f6.gray.lh-title
          "No articles found."]]
        (list
         ;; Special documents (Readme & Changelog)
         (when (seq readme-and-changelog)
           [:div.mv3
            (articles/doc-tree-view version-entity readme-and-changelog (:doc-slug-path route-params))])
         (when (seq doc-tree-with-rest)
           [:div.mb3 (articles/doc-tree-view version-entity doc-tree-with-rest (:doc-slug-path route-params))])))]

     ;; Namespace listing
     (let [ns-entities (bundle/ns-entities cache-bundle)]
       [:div.mb4
        (layout/sidebar-title "Namespaces")
        (if (seq ns-entities)
          (api/namespace-list {:current (:namespace route-params)
                               :version-entity version-entity}
                              ns-entities)
          [:p.f7.gray.lh-title
           (if (build-log/api-import-successful? last-build)
             "None found"
             "API analysis failed")])])]))

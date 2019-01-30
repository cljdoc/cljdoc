(ns cljdoc.render.sidebar
  (:require [cljdoc.util :as util]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.articles :as articles]
            [cljdoc.render.api :as api]
            [cljdoc.bundle :as bundle]))

(defn sidebar-contents
  "Render a sidebar for a documentation page.

  This function takes the same arguments as the main render functions in `cljdoc.renderer.html`
  and selected pages/namespaces will be highlighted based on the supplied `route-params`.

  If articles or namespaces are missing for a project there will be little messages pointing
  users to the relevant documentation or GitHub to open an issue."
  [route-params {:keys [version-entity] :as cache-bundle}]
  (let [doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> cache-bundle :version :doc))
        split-doc-tree ((juxt filter remove)
                        #(contains? #{"Readme" "Changelog"} (:title %))
                        doc-tree)
        readme-and-changelog (first split-doc-tree)
        doc-tree-with-rest (second split-doc-tree)]
    [;; Upgrade notice
     (if-let [newer-v (bundle/more-recent-version cache-bundle)]
       (layout/upgrade-notice newer-v))

     ;; Special documents (Readme & Changelog)
     (when (seq readme-and-changelog)
       [:div.mb4
        (articles/doc-tree-view version-entity readme-and-changelog (:doc-slug-path route-params))])

     ;; Remaining doctree or note if missing
     (cond
       ;; custom doctree has been provided and so we can assume the authors are aware of
       ;; cljdoc's articles feature -> no further notes required
       (seq doc-tree-with-rest)
       [:div.mb4.js--articles
        (layout/sidebar-title "Articles" {:separator-line? (not (empty? readme-and-changelog))})
        [:div.mv3 (articles/doc-tree-view version-entity doc-tree-with-rest (:doc-slug-path route-params))]]

       ;; only readme and changelog -> inform user about custom articles
       (seq readme-and-changelog)
       [:div.mb4
        [:p.f7.gray.lh-title
         [:a.blue.link {:href (util/github-url :userguide/articles)} "Articles"]
         " are a practical way to provide additional guidance beyond
       API documentation. Please refer to " [:a.blue.link {:href (util/github-url
       :userguide/articles)} "the documentation"] " to learn more about using them."]]

       ;; no articles at all -> list common problems + link to docs
       :else
       [:div.mb4
        [:p.f7.gray.lh-title
         "We couldn't find a Readme or any other articles for this project. This happens when
         we could not find the Git repository for a project or there are no articles present in
         a format that cljdoc supports. " [:strong "Please consult the " [:a.blue.link {:href
         (util/github-url :userguide/articles)} "cljdoc docs"] " on how to fix this."]]])

     ;; Namespace listing
     (let [ns-entities (bundle/ns-entities cache-bundle)]
       [:div.mb4
        (layout/sidebar-title "Namespaces")
        (if (seq ns-entities)
          (api/namespace-list {:current (:namespace route-params)} ns-entities)
          [:p.f7.gray.lh-title
           "We couldn't find any namespaces in this artifact. Most often the reason for this is
           that the analysis failed or that the artifact has been mispackaged and does not
           contain any Clojure source files. The latter might be on purpose for uber-module
           style artifacts. " "Please " [:a.blue.link {:href (util/github-url :issues)} "open
           an issue"] " and we'll be happy to look into it."])])]))

(ns index
  (:require ["preact" :refer [render]]
            ["./dom" :as dom]
            ["./switcher" :refer [trackProjectOpened Switcher]]
            ["./search" :refer [App]]
            ["./mobile" :refer [MobileNav]]
            ["./navigator" :refer [Navigator]]
            ["./cljdoc" :refer [isNSPage isNSOverviewPage isNSOfflinePage isProjectDocumentationPage
                                initScrollIndicator initToggleRaw restoreSidebarScrollPos
                                toggleMetaDialog toggleArticlesTip addPrevNextPageKeyHandlers
                                saveSidebarScrollPos]]
            ["./recent_doc_links" :refer [initRecentDocLinks]]
            ["./single_docset_search" :refer [mount-single-docset-search]]
            ["./hljs-merge-plugin" :refer [mergeHTMLPlugin]]
            ["./hljs_copy_button_plugin" :refer [copyButtonPlugin]]))

(trackProjectOpened)

(when-let [switcher-node (dom/query-doc "[data-id='cljdoc-switcher']")]
  (render #jsx [:Switcher]
          switcher-node))

(let [search-node (dom/query-doc "[data-id='cljdoc-search']")]
  (when (and search-node (.-dataset search-node))
    (render #jsx [:App {:initialValue (-> search-node .-dataset .-initialValue)
                        :results []
                        :focused false
                        :selectedIndex 0}]
            search-node)))

(when-let [navigator-node (dom/query-doc "[data-id='cljdoc-js--cljdoc-navigator']")]
  (render #jsx [:Navigator] navigator-node))

(when (isNSOverviewPage)
  (initToggleRaw))

(when (isNSPage)
  (initScrollIndicator)
  (initToggleRaw))

(when (isNSOfflinePage)
  (initToggleRaw))

(when (isProjectDocumentationPage)
  (when-let [mobile-nav-node (dom/query-doc "[data-id='cljdoc-js--mobile-nav']")]
    (render #jsx [:MobileNav]
            mobile-nav-node))
  (toggleMetaDialog)
  (toggleArticlesTip)
  (addPrevNextPageKeyHandlers))

(when-let [recently-visited-node (dom/query-doc "[data-id='cljdoc-doc-links']")]
  (initRecentDocLinks recently-visited-node))

(mount-single-docset-search)

(.addEventListener js/document "DOMContentLoaded"
                   (fn []
                     (saveSidebarScrollPos)
                     (restoreSidebarScrollPos)))

(set! js/window.mergeHTMLPlugin mergeHTMLPlugin)
(set! js/window.copyButtonPlugin copyButtonPlugin)

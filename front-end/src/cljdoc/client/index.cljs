(ns cljdoc.client.index
  (:require ["./hljs-merge-plugin" :refer [mergeHTMLPlugin]]
            ["preact" :refer [h render]]
            [cljdoc.client.cljdoc :refer [addPrevNextPageKeyHandlers
                                          initScrollIndicator initToggleRaw
                                          isNSOfflinePage isNSOverviewPage
                                          isNSPage isProjectDocumentationPage
                                          restoreSidebarScrollPos
                                          saveSidebarScrollPos
                                          toggleArticlesTip toggleMetaDialog]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.hljs-copy-button-plugin :refer [copyButtonPlugin]]
            [cljdoc.client.mobile :refer [MobileNav]]
            [cljdoc.client.navigator :refer [Navigator]]
            [cljdoc.client.recent-doc-links :refer [initRecentDocLinks]]
            [cljdoc.client.search :refer [App]]
            [cljdoc.client.single-docset-search :refer [mount-single-docset-search]]
            [cljdoc.client.switcher :refer [Switcher trackProjectOpened]]))

(trackProjectOpened)

(when-let [switcher-node (dom/query-doc "[data-id='cljdoc-switcher']")]
  (render (h Switcher)
          switcher-node))

(let [search-node (dom/query-doc "[data-id='cljdoc-search']")]
  (when (and search-node (.-dataset search-node))
    (render (h App {:initialValue (-> search-node .-dataset .-initialValue)
                     :results []
                     :focused false
                     :selectedIndex 0})
            search-node)))

(when-let [navigator-node (dom/query-doc "[data-id='cljdoc-js--cljdoc-navigator']")]
  (render (h Navigator) navigator-node))

(when (isNSOverviewPage)
  (initToggleRaw))

(when (isNSPage)
  (initScrollIndicator)
  (initToggleRaw))

(when (isNSOfflinePage)
  (initToggleRaw))

(when (isProjectDocumentationPage)
  (when-let [mobile-nav-node (dom/query-doc "[data-id='cljdoc-js--mobile-nav']")]
    (render (h MobileNav)
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

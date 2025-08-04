(ns cljdoc.client.index
  (:require ["./hljs-merge-plugin" :refer [mergeHTMLPlugin]]
            ["preact" :refer [h render]]
            [cljdoc.client.cljdoc :as cljdoc]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.hljs-copy-button-plugin :refer [copyButtonPlugin]]
            [cljdoc.client.mobile :refer [MobileNav]]
            [cljdoc.client.navigator :refer [Navigator]]
            [cljdoc.client.recent-doc-links :as recent-doc-links]
            [cljdoc.client.search :refer [LibSearch]]
            [cljdoc.client.single-docset-search :as single-docset-search]
            [cljdoc.client.switcher :refer [Switcher] :as switcher]))

(switcher/track-project-opened)

(when-let [switcher-node (dom/query-doc "[data-id='cljdoc-switcher']")]
  (render (h Switcher)
          switcher-node))

(let [search-node (dom/query-doc "[data-id='cljdoc-search']")]
  (when (and search-node (.-dataset search-node))
    (render (h LibSearch {:initialValue (-> search-node .-dataset .-initialValue)
                    :results []
                    :focused false
                    :selectedIndex 0})
            search-node)))

(when-let [navigator-node (dom/query-doc "[data-id='cljdoc-js--cljdoc-navigator']")]
  (render (h Navigator) navigator-node))

(when (cljdoc/is-namespace-overview-page)
  (cljdoc/init-toggle-docstring-raw))

(when (cljdoc/is-namespace-page)
  (cljdoc/init-scroll-indicator)
  (cljdoc/init-toggle-docstring-raw))

(when (cljdoc/is-namespace-offline-page)
  (cljdoc/init-toggle-docstring-raw))

(when (cljdoc/is-project-doc-page)
  (when-let [mobile-nav-node (dom/query-doc "[data-id='cljdoc-js--mobile-nav']")]
    (render (h MobileNav)
            mobile-nav-node))
  (cljdoc/toggle-meta-dialog)
  (cljdoc/toggle-articles-tip)
  (cljdoc/add-next-prev-key-for-articles))

(when-let [recently-visited-node (dom/query-doc "[data-id='cljdoc-doc-links']")]
  (recent-doc-links/init recently-visited-node))

(single-docset-search/init)

(.addEventListener js/document "DOMContentLoaded"
                   (fn []
                     (cljdoc/save-sidebar-scroll-pos)
                     (cljdoc/restore-sidebar-scroll-pos)))

(set! js/window.mergeHTMLPlugin mergeHTMLPlugin)
(set! js/window.copyButtonPlugin copyButtonPlugin)

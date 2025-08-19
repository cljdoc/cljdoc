(ns client.namespace-scroll
  "Support for scrolling through namespace"
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]
            [cljdoc.client.page :as page]))

(warn-on-lazy-reusage!)

(defn- init-scroll-indicator []
  (let [def-detail-scroll-view (dom/query ".js--main-scroll-view")
        def-list-scroll-view (dom/query ".js--namespace-contents-scroll-view")
        def-detail-blocks (dom/query-all ".def-block" def-detail-scroll-view)
        def-list-items (dom/query-all ".def-item" def-list-scroll-view)
        is-elem-visible? (fn [container el]
                           (let [{:keys [y height]} (.getBoundingClientRect el)
                                 etop y
                                 ebottom (+ etop height)
                                 cbottom (.-innerHeight js/window)
                                 ctop (- cbottom (.-clientHeight container))]
                             (and (<= etop cbottom) (>= ebottom ctop))))
        draw-scroll-indicator (fn []
                                (doseq [[idx el] (map-indexed vector def-detail-blocks)]
                                  (let [def-item (get def-list-items idx)]
                                    (.log js/console "item" (is-elem-visible? def-detail-scroll-view el) def-item)
                                    (if-not (and def-detail-scroll-view
                                                 def-list-scroll-view
                                                 (is-elem-visible? def-detail-scroll-view el))
                                      (dom/remove-class def-item "scroll-indicator")
                                      (do
                                        (dom/add-class def-item "scroll-indicator")
                                        (cond
                                          (zero? idx)
                                          (set! def-list-scroll-view.scrollTop 1)

                                          (not (is-elem-visible? def-list-scroll-view def-item))
                                          (.scrollIntoView def-item)))))))]
    (when def-detail-scroll-view
      (.addEventListener def-detail-scroll-view "scroll" draw-scroll-indicator))

    (draw-scroll-indicator)))

(defn- is-element-out-of-view
  "Returns true if `elem` is out of view but can, in theory, be scrolled down to."
  [elem]
  (let [rect (.getBoundingClientRect elem)]
    (or (> (.-top rect) (.-innerHeight js/window))
        (< (.-bottom rect) 0))))

(defn- restore-sidebar-scroll-pos
  "Cljdoc always loads a full page.
  This means the sidebar nav scoll position needs to be restored/set."
  []
  (when-let [main-side-bar (dom/query ".js--main-sidebar")]
    (let [sidebar-scroll-state (.parse js/JSON (or (.getItem js/sessionStorage "sidebarScroll") "null"))]
      (.removeItem js/sessionStorage "sidebarScroll")
      (when-not js/window.location.search
        (if (and sidebar-scroll-state
                 (= (lib/coords-path-from-current-loc) (.-libVersionPath sidebar-scroll-state)))
          (set! (.-scrollTop main-side-bar) (.-scrollTop sidebar-scroll-state))
          (when-let [selected-elem (dom/query "a.b" main-side-bar)]
            (when (is-element-out-of-view selected-elem)
              (.scrollIntoView selected-elem {:behavior "instant"
                                              :block "start"}))))))))

(defn- save-sidebar-scroll-pos
  "Support for restoreSidebarScrollPos
  When item in sidebar is clicked saves scroll pos and lib/version to session."
  []
  (when-let [main-side-bar (dom/query ".js--main-sidebar")]
    (let [anchor-elems (dom/query-all "a" main-side-bar)]
      (doseq [anchor anchor-elems]
        (.addEventListener anchor "click"
                           (fn []
                             (let [scroll-top (.-scrollTop main-side-bar)
                                   data {:libVersionPath (lib/coords-path-from-current-loc)
                                         :scrollTop scroll-top}]
                               (.setItem js/sessionStorage "sidebarScroll" (.stringify js/JSON data)))))))))

(defn init []
  (when (page/is-namespace)
    (init-scroll-indicator)
    (.addEventListener js/document "DOMContentLoaded"
                       (fn []
                         (save-sidebar-scroll-pos)
                         (restore-sidebar-scroll-pos)))))

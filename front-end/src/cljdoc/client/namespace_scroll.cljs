(ns client.namespace-scroll
  "Support for scrolling through namespace"
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]
            [cljdoc.client.page :as page]))

(defn- init-scroll-indicator []
  (let [def-detail-scroll-view (dom/query ".js--main-scroll-view")
        def-nav-scroll-view (dom/query ".js--namespace-contents-scroll-view")
        def-detail-blocks (dom/query-all ".def-block" def-detail-scroll-view)
        def-nav-items (dom/query-all ".def-item" def-nav-scroll-view)
        is-elem-visible? (fn [container el]
                           (let [{:keys [y height]} (.getBoundingClientRect el)
                                 etop y
                                 ebottom (+ etop height)
                                 cbottom (.-innerHeight js/window)
                                 ctop (- cbottom (.-clientHeight container))]
                             (and (<= etop cbottom) (>= ebottom ctop))))
        draw-scroll-indicator (fn []
                                (loop [ndx (count def-detail-blocks)
                                       in-indicator-block false
                                       indicator-block-found false]
                                  (let [ndx (dec ndx)]
                                    (when (>= ndx 0)
                                      (let [detail-el (get def-detail-blocks ndx)
                                            show-indicator? (and def-detail-scroll-view
                                                                 def-nav-scroll-view
                                                                 (not indicator-block-found)
                                                                 (is-elem-visible? def-detail-scroll-view detail-el))
                                            nav-item-el (get def-nav-items ndx)]
                                        (if-not show-indicator?
                                          (dom/remove-class nav-item-el "scroll-indicator")
                                          (do
                                            (dom/add-class nav-item-el "scroll-indicator")
                                            (cond
                                              (zero? ndx)
                                              (set! def-nav-scroll-view.scrollTop 1)

                                              (not (is-elem-visible? def-nav-scroll-view nav-item-el))
                                              (.scrollIntoView nav-item-el))))
                                        (recur ndx
                                               show-indicator?
                                               (or indicator-block-found
                                                   (and (not show-indicator?) in-indicator-block))))))))]
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

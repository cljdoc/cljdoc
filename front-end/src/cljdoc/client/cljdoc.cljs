(ns cljdoc.client.cljdoc
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as library]))

(defn isNSOverviewPage []
  (boolean (dom/query-doc ".ns-overview-page")))

(defn isNSPage []
  (boolean (dom/query-doc ".ns-page")))

(defn isNSOfflinePage []
  (boolean (dom/query-doc ".ns-offline-page")))

(defn isProjectDocumentationPage []
  (library/coords-from-current-loc))

(defn initScrollIndicator []
  (let [main-scroll-view (dom/query-doc ".js--main-scroll-view")
        sidebar-scroll-view (dom/query-doc ".js--namespace-contents-scroll-view")
        def-blocks (dom/query-doc-all ".def-block")
        def-items (dom/query-doc-all ".def-item")
        is-elem-visible? (fn [container el]
                           (let [{:keys [y height]} (.getBoundingClientRect el)
                                 etop y
                                 ebottom (+ etop height)
                                 cbottom (.-innerHeight js/window)
                                 ctop (- cbottom (.-clientHeight container))]
                             (and (<= etop cbottom) (>= ebottom ctop))))
        draw-scroll-indicator (fn []
                                (doseq [[idx el] (map-indexed vector def-blocks)]
                                  (let [def-item (get def-items idx)]
                                    (if-not (and main-scroll-view
                                                 sidebar-scroll-view
                                                 (is-elem-visible? main-scroll-view el))
                                      (dom/remove-class def-item "scroll-indicator")
                                      (do
                                        (dom/add-class def-item "scroll-indicator")
                                        (cond
                                          (zero? idx)
                                          (set! sidebar-scroll-view.scrollTop 1)

                                          (not (is-elem-visible? sidebar-scroll-view def-item))
                                          (.scrollIntoView def-item)))))))]
    (when main-scroll-view
      (.addEventListener main-scroll-view "scroll" draw-scroll-indicator))

    (draw-scroll-indicator)))

(defn initToggleRaw []
  (let [toggles (dom/query-doc-all ".js--toggle-raw")
        add-toggle-handlers (fn []
                              (doseq [el toggles]
                                (.addEventListener el "click"
                                                   (fn []
                                                     (let [parent (.-parentElement el)
                                                           markdowns (dom/query-doc-all ".markdown" parent)
                                                           raws (dom/query-doc-all ".raw" parent)]
                                                       (doseq [[ndx markdown] (map-indexed vector markdowns)]
                                                         (let [raw (when raws (get raws ndx))
                                                               hide "dn"]
                                                           (if (dom/has-class? markdown hide)
                                                             (do
                                                               (dom/remove-class markdown hide)
                                                               (when raw (dom/add-class raw hide))
                                                               (set! (.-innerText el) "raw docstring"))
                                                             (do
                                                               (dom/add-class markdown hide)
                                                               (when raw (dom/remove-class raw hide))
                                                               (set! (.-innerText el) "formatted docstring"))))))))))]
    (add-toggle-handlers)))

(defn- is-element-out-of-view
  "Returns true if `elem` is out of view but can, in theory, be scrolled down to."
  [elem]
  (let [rect (.getBoundingClientRect elem)]
    (or (> (.-top rect) (.-innerHeight js/window))
        (< (.-bottom rect) 0))))

(defn restoreSidebarScrollPos
  "Cljdoc always loads a full page.
  This means the sidebar nav scoll position needs to be restored/set."
  []
  (when-let [main-side-bar (dom/query-doc ".js--main-sidebar")]
    (let [sidebar-scroll-state (.parse js/JSON (or (.getItem js/sessionStorage "sidebarScroll") "null"))]
      (.removeItem js/sessionStorage "sidebarScroll")
      (when-not js/window.location.search
        (if (and sidebar-scroll-state
                 (= (library/coords-path-from-current-loc) (.-libVersionPath sidebar-scroll-state)))
          (set! (.-scrollTop main-side-bar) (.-scrollTop sidebar-scroll-state))
          (when-let [selected-elem (dom/query-doc "a.b" main-side-bar)]
            (when (is-element-out-of-view selected-elem)
              (.scrollIntoView selected-elem {:behavior "instant"
                                              :block "start"}))))))))

(defn saveSidebarScrollPos
  "Support for restoreSidebarScrollPos
  When item in sidebar is clicked saves scroll pos and lib/version to session."
  []
  (when-let [main-side-bar (dom/query-doc ".js--main-sidebar")]
    (let [anchor-elems (dom/query-doc-all "a" main-side-bar)]
      (doseq [anchor anchor-elems]
        (.addEventListener anchor "click"
                           (fn []
                             (let [scroll-top (.-scrollTop main-side-bar)
                                   data {:libVersionPath (library/coords-path-from-current-loc)
                                         :scrollTop scroll-top}]
                               (.setItem js/sessionStorage "sidebarScroll" (.stringify js/JSON data)))))))))

(defn toggleMetaDialog []
  (when (dom/query-doc ".js--main-scroll-view")
    (let [meta-icon (dom/query-doc "[data-id='cljdoc-js--meta-icon']")
          meta-dialog (dom/query-doc "[data-id='cljdoc-js--meta-dialog']")
          meta-close (dom/query-doc "[data-id='cljdoc-js--meta-close']")
          display "db-ns"
          hide "dn"]
      (when meta-icon
        (.addEventListener meta-icon "click"
                           (fn []
                             (dom/replace-class meta-icon display hide)
                             (when meta-dialog
                               (dom/replace-class meta-dialog hide display)))))
      (when meta-close
        (.addEventListener meta-close "click"
                           (fn []
                             (dom/replace-class meta-dialog display hide)
                             (when meta-icon
                               (dom/replace-class meta-icon hide display))))))))

(defn toggleArticlesTip []
  (let [tip-toggler (dom/query-doc "[data-id='cljdoc-js--articles-tip-toggler']")
        tip (dom/query-doc "[data-id='cljdoc-js--articles-tip']")]
    (when (and tip-toggler tip)
      (.addEventListener tip-toggler "click"
                         (fn [] (dom/toggle-class tip "dn"))))))

(defn addPrevNextPageKeyHandlers []
  (let [prev-link (dom/query-doc "a[data-id='cljdoc-prev-article-page-link']")
        next-link (dom/query-doc "a[data-id='cljdoc-next-article-page-link']")]
    (when (or prev-link next-link)
      (.addEventListener js/document "keydown"
                         (fn [e]
                           (let [code (.-code e)]
                             (when (and (= "ArrowLeft" code) prev-link)
                               (set! js/document.location.href (.-href prev-link)))
                             (when (and (= "ArrowRight" code) next-link)
                               (set! js/document.location.href (.-href next-link)))))))))

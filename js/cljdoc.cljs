(ns cljdoc)

(defn- query-doc
  ([q elem]
   (when elem (.querySelector elem q)))
  ([q] (query-doc q document)))

(defn- query-doc-all
  ([q elem]
   (.log console "qsa" q elem)
   (when elem
     (.querySelectorAll elem q)))
  ([q] (query-doc-all q document)))

(defn isNSOverviewPage []
  (boolean (query-doc ".ns-overview-page")))

(defn isNSPage []
  (boolean (query-doc ".ns-page")))

(defn isNSOfflinePage []
  (boolean (query-doc ".ns-offline-page")))

(defn isProjectDocumentationPage []
  (let [path-segs  (-> window.location.pathname
                       (.split "/"))]
    (and (>= (count path-segs) 5)
         (= "d" (second path-segs)))))

(defn initScrollIndicator []
  (let [main-scroll-view (query-doc ".js--main-scroll-view")
        sidebar-scroll-view (query-doc ".js--namespace-contents-scroll-view")
        def-blocks (query-doc-all ".def-block")
        def-items (query-doc-all ".def-item")
        is-elem-visible (fn [container el]
                          (let [{:keys [y height] } (.getBoundingClientRect el)
                                etop y
                                ebottom (+ etop height)
                                cbottom (.-innerHeight window)
                                ctop (- cbottom (.-clientHeight container))]
                            (and (<= etop cbottom) (>= ebottom ctop))))
        draw-scroll-indicator (fn []
                                (doseq [[idx el] (map-indexed vector def-blocks)]
                                  (let [def-item (get def-items idx)]
                                    (if-not (and main-scroll-view
                                             sidebar-scroll-view
                                             (is-elem-visible main-scroll-view el))
                                      (.remove (.-classList def-item) "scroll-indicator")
                                      (do
                                        (.add (.-classList def-item) "scroll-indicator")
                                        (cond
                                          (zero? idx)
                                          (set! sidebar-scroll-view.scrollTop 1)

                                          (not (is-elem-visible sidebar-scroll-view def-item))
                                          (.scrollIntoView def-item)))))))]
    (when main-scroll-view
      (.addEventListener main-scroll-view "scroll" draw-scroll-indicator))

    (draw-scroll-indicator)))

(defn initToggleRaw []
  (let [toggles (query-doc-all ".js--toggle-raw")
        add-toggle-handlers (fn []
                              (doseq [[ndx el] (map-indexed vector toggles)]
                                (.addEventListener el "click"
                                                   (fn []
                                                     (let [parent (.-parentElement el)
                                                           markdowns (query-doc-all ".markdown" parent)
                                                           raws (query-doc-all ".raw" parent)]
                                                       (doseq [[ndx markdown] (map-indexed vector markdowns)]
                                                         (let [raw (when raws (get raws idx))]
                                                           (if (.contains (.-classList markdown) "dn")
                                                             (do
                                                               (.remove (.-classList markdown) "dn")
                                                               (when raw (.add (.-classList raw) "dn"))
                                                               (.innerText el "raw docstring"))
                                                             (do
                                                               (.add (.-classlist markdown) "dn")
                                                               (when raw (.remove (.classList raw) "dn"))
                                                               (.innerText el "formatted docstring"))))))))))]
    (add-toggle-handlers)))

(defn- lib-version-path
  "Returns lib and version portion of current location.
  Example: /d/clj-commons/clj-yaml/1.0.27"
  []
  (-> window.location.pathname
      (.split "/")
      (.slice 0 5)
      (.join "/")))

(defn- is-element-out-of-view
  "Returns true if `elem` is out of view but can, in theory, be scrolled down to."
  [elem]
  (let [rect (.getBoundingClientRect elem)]
    (or (> (.-top rect) (.-innerHeight window))
        (< (.-bottom rect) 0))))

(defn restoreSidebarScrollPos
  "Cljdoc always loads a full page.
  This means the sidebar nav scoll position needs to be restored/set."
  []
  (when-let [main-side-bar (query-doc ".js--main-sidebar")]
    (let [sidebar-scroll-state (JSON/parse (or (.getItem sessionStorage "sidebarScroll") "null"))]
      (.removeItem sessionStorage "sidebarScroll")
      (when-not window.location.search
        (if (and sidebar-scroll-state
                 (= (lib-version-path) (.-libVersionPath sidebar-scroll-state)))
          (set! (.-scrollTop main-side-bar) (.-scrollTop sidebar-scroll-state))
          (when-let [selected-elem (query-doc "a.b" main-side-bar)]
            (when (is-element-out-of-view selected-elem)
              (.scrollIntoView selected-elem {:behavior "instant"
                                              :block "start"}))))))))

(defn saveSidebarScrollPos
 "Support for restoreSidebarScrollPos
  When item in sidebar is clicked saves scroll pos and lib/version to session."
  []
  (when-let [main-side-bar (query-doc ".js--main-sidebar")]
    (let [anchor-elems (query-doc-all "a" main-side-bar)]
      (doseq [anchor anchor-elems]
        (.addEventListener anchor "click"
                           (fn []
                             (let [scroll-top (.-scrollTop main-side-bar)
                                   data {:libVersionPath (lib-version-path)
                                         :scrollTop scroll-top}]
                               (.setItem sessionStorage "sidebarScroll" (JSON/stringify data)))))))))

(defn toggleMetaDialog []
  (when (query-doc ".js--main-scroll-view")
    (let [meta-icon (query-doc "[data-id='cljdoc-js--meta-icon']")
          meta-dialog (query-doc "[data-id='cljdoc-js--meta-dialog']")
          meta-close (query-doc "[data-id='cljdoc-js--meta-close']")]
      (when meta-icon
        (.addEventListener meta-icon "click"
                           (fn []
                             (.replace (.-classList meta-icon) "db-ns" "dn")
                             (when meta-dialog
                               (.replace (.-classList meta-dialog) "dn" "db-ns")))))
      (when meta-close
        (.addEventListener meta-close "click"
                           (fn []
                             (.replace (.-classList meta-dialog) "db-ns" "dn")
                             (when meta-icon
                               (.replace (.-classList meta-icon) "dn" "db-ns"))))))))


(defn toggleArticlesTip []
  (let [tip-toggler (query-doc "[data-id='cljdoc-js--articles-tip-toggler']")
        tip (query-doc "[data-id='cljdoc-js--articles-tip']")]
    (when (and tip-toggler tip)
      (.set (.onClick tip-toggler)
            (fn [] (.toggle (.-classList tip) "dn"))))))

(defn addPrevNextPageKeyHandlers []
  (let [prev-link (query-doc "a[data-id='cljdoc-prev-article-page-link']")
        next-link (query-doc "a[data-id='cljdoc-next-article-page-link']")]
    (when (or prev-link next-link)
      (.addEventListener document "keydown"
                         (fn [e]
                           (let [code (.-code e)]
                             (when (and (= "ArrowLeft" code) prev-link)
                               (set! document.location.href (.-href prev-link)))
                             (when (and (= "ArrowRight" code) next-link)
                               (set! document.location.href (.-href next-link)))))))))

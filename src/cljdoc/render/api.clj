(ns cljdoc.render.api
  "Functions related to rendering API documenation"
  (:require [cljdoc.render.rich-text :as rich-text]
            [cljdoc.util.ns-tree :as ns-tree]
            [cljdoc.util :as util]
            [cljdoc.bundle :as bundle]
            [cljdoc.platforms :as platf]
            [cljdoc.spec]
            [cljdoc.server.routes :as routes]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]
            [zprint.core :as zp]))

(defn def-code-block
  [args-str]
  {:pre [(string? args-str)]}
  [:pre
   [:code.db.mb2.pa0 {:class "language-clojure"}
    (zp/zprint-str args-str {:parse-string? true :width 70})]])

(defn parse-wiki-link [m]
  (if (.contains m "/")
    (let [[ns var] (string/split m #"/")]
      [ns var])
    ;; While in theory vars can contain dots it's fairly uncommon and
    ;; so we assume if there's no / and a dot that the user is
    ;; referring to a namespace
    (if (.contains m ".")
      [m nil]
      [nil m])))

(defn docstring->html [doc-str render-wiki-link]
  [:div
   [:div.lh-copy.markdown
    ;; If someone sets `{:doc false}`, there will be no docstring
    (when doc-str
      (-> doc-str
          (rich-text/markdown-to-html
            {:escape-html? true
             :render-wiki-link (comp render-wiki-link parse-wiki-link)})
          hiccup/raw))]
   [:pre.lh-copy.bg-near-white.code.pa3.br2.f6.overflow-x-scroll.dn.raw
    doc-str]])

(defn render-wiki-link-fn
  "Given the `current-ns` and a function `ns-link-fn` that is assumed
  to return a link to a passed namespace, return a function that receives
  a `[ns var]` tuple and will return a Markdown link to the specified ns/var."
  [current-ns ns-link-fn]
  (fn render-wiki-link-inner [[ns var]]
    (str (ns-link-fn (if ns (util/replant-ns current-ns ns) current-ns))
         (when var (str "#" var)))))

(defn render-doc [mp render-wiki-link]
  (if (platf/varies? mp :doc)
    (for [p (sort (platf/platforms mp))
          :when (platf/get-field mp :doc p)]
      [:div.relative
       [:div.f7.gray.absolute-ns.nl5-ns.pr2-ns.tr-ns.w3.pv1.nb3
        [:span.db-ns.dn p]
        [:span.dn-ns (get {"clj" "Clojure" "cljs" "ClojureScript"} p)]]
       (some-> (platf/get-field mp :doc p) (docstring->html render-wiki-link))])
    (some-> (platf/get-field mp :doc) (docstring->html render-wiki-link))))

(defn render-arglists [def-name arglists]
  (for [argv (sort-by count arglists)]
    (def-code-block
      (str "(" def-name (when (seq argv) " ") (string/join " " argv) ")"))))

(defn def-block
  [def render-wiki-link]
  {:pre [(platf/multiplatform? def)]}
  (let [def-name (platf/get-field def :name)]
    [:div.def-block
     [:hr.mv3.b--black-10]
     [:h4.def-block-title.mv0.pv3
      {:name (platf/get-field def :name), :id def-name}
      def-name
      (when-not (= :var (platf/get-field def :type))
        [:span.f7.ttu.normal.gray.ml2 (platf/get-field def :type)])
      (when (platf/get-field def :deprecated)
        [:span.fw3.f6.light-red.ml2 "deprecated"])]
     [:div.lh-copy
      (if (platf/varies? def :arglists)
        (for [p (sort (platf/platforms def))
              :when (platf/get-field def :arglists p)]
          [:div
           [:span.f7.ttu.gray.db.nb2 (get {"clj" "Clojure" "cljs" "ClojureScript"} p) " arglists"]
           (render-arglists def-name (platf/get-field def :arglists p))])
        (render-arglists def-name (platf/get-field def :arglists)))]
     (render-doc def render-wiki-link)
     (when (seq (platf/get-field def :members))
       [:div.lh-copy.pl3.bl.b--black-10
        (for [m (platf/get-field def :members)]
          [:div
           [:h5 (:name m)]
           (render-arglists (:name m) (:arglists m))
           (when (:doc m)
             [:p (docstring->html (:doc m) render-wiki-link)])])])
     (when (or (platf/varies? def :src-uri) ; if it varies they can't be both nil
               (platf/get-field def :src-uri)) ; if it doesn't vary, ensure non-nil
       (if (platf/varies? def :src-uri)
         (for [p (sort (platf/platforms def))
               :when (platf/get-field def :src-uri p)]
           [:a.link.f7.gray.hover-dark-gray.mr2
            {:href (platf/get-field def :src-uri p)}
            (format "source (%s)" p)])
         [:a.link.f7.gray.hover-dark-gray.mr2 {:href (platf/get-field def :src-uri)} "source"]))
     (when (seq (platf/all-vals def :doc))
       [:a.link.f7.gray.hover-dark-gray.js--toggle-raw {:href "#"} "raw docstring"])]))

(defn namespace-list [{:keys [current version-entity]} namespaces]
  (let [keyed-namespaces (ns-tree/index-by :namespace namespaces)
        from-dependency? (fn from-dependency? [ns-entity]
                           (or (not= (:group-id version-entity) (:group-id ns-entity))
                               (not= (:artifact-id version-entity) (:artifact-id ns-entity)))) ]
    [:div
     [:ul.list.pl0
      (for [[ns level _ _leaf?] (ns-tree/namespace-hierarchy (keys keyed-namespaces))
            :let [style {:margin-left (str (* (dec level) 10) "px")}
                  nse (get keyed-namespaces ns)]]
        [:li
         [:a.link.hover-dark-blue.blue.dib.pa1
          {:href (routes/url-for :artifact/namespace :path-params (assoc version-entity :namespace ns))
           :class (when (= ns current) "b")
           :style style}
          (->> (ns-tree/split-ns ns)
               (drop (dec level)))
          (when (and nse (from-dependency? nse))
            [:sup.pl1.normal "†"])]])]
     (when (some from-dependency? namespaces)
       [:p.f7.fw5.gray.mt4 [:sup.f6.db "†"] "Included via a transitive dependency."])]))

(defn humanize-supported-platforms
  ([supported-platforms]
   (humanize-supported-platforms supported-platforms :short))
  ([supported-platforms style]
   (case style
     :short (case supported-platforms
              #{"clj" "cljs"} "clj/s"
              #{"clj"}        "clj"
              #{"cljs"}       "cljs")
     :long  (case supported-platforms
              #{"clj" "cljs"} "Clojure & ClojureScript"
              #{"clj"}        "Clojure"
              #{"cljs"}       "ClojureScript"))))

(defn platform-stats [defs]
  (let [grouped-by-platform-support (->> defs
                                         (map platf/platforms)
                                         (group-by identity))
        counts-by-platform (-> grouped-by-platform-support
                               (update #{"clj"} count)
                               (update #{"cljs"} count)
                               (update #{"clj" "cljs"} count))]
    (->> counts-by-platform (sort-by val) reverse (filter (comp pos? second)))))

(defn platform-support-note [[[dominant-platf] :as platf-stats]]
  (let [node :span.f7.fw5.gray]
    (if (= 1 (count platf-stats))
      (if (or (= dominant-platf #{"clj"})
              (= dominant-platf #{"cljs"}))
        [node (str (humanize-supported-platforms dominant-platf :long) " only.")]
        #_[node "All forms support " (str (humanize-supported-platforms dominant-platf :long) ".")]
        [node "All platforms."])
      [node (str "Mostly " (humanize-supported-platforms dominant-platf) " forms.")
       [:br] " Exceptions indicated."])))

(defn definitions-list [_ns-entity defs {:keys [indicate-platforms-other-than]}]
  [:div.pb4
   [:ul.list.pl0
    (for [def defs
          :let [def-name (platf/get-field def :name)]]
      [:li.def-item
       [:a.link.dim.blue.dib.pa1.pl0
        {:href (str "#" def-name)}
        def-name]
       (when-not (= (platf/platforms def)
                    indicate-platforms-other-than)
         [:sup.f7.gray
          (-> (platf/platforms def)
              (humanize-supported-platforms))])])]])

(defn namespace-overview
  [ns-url-fn mp-ns defs]
  {:pre [(platf/multiplatform? mp-ns) (fn? ns-url-fn)]}
  (let [ns-name (platf/get-field mp-ns :name)]
    [:div
     [:a.link.black
      {:href (ns-url-fn ns-name)}
      [:h2
       {:data-cljdoc-type "namespace"}
       ns-name
       [:img.ml2 {:src "https://icon.now.sh/chevron/12/357edd"}]]]
     (render-doc mp-ns (render-wiki-link-fn ns-name ns-url-fn))
     (if-not (seq defs)
       [:p.i.blue "No vars found in this namespace."]
       [:ul.list.pl0
        (for [d defs
              :let [def-name (platf/get-field d :name)
                    type (if (seq (platf/all-vals d :arglists))
                           :function
                           (platf/get-field d :type))]]
          [:li.dib.mr3.mb2
           [:a.link.blue
            {:data-cljdoc-type (name type)
             :href (str (ns-url-fn ns-name) "#" def-name)}
            def-name]])])]))

(defn sub-namespace-overview-page
  [{:keys [ns-entity namespaces defs]}]
  [:div.mw7.center.pv4
   (for [mp-ns (->> namespaces
                    (filter #(.startsWith (platf/get-field % :name) (:namespace ns-entity))))
         :let [ns (platf/get-field mp-ns :name)
               ns-url-fn #(routes/url-for :artifact/namespace :path-params (assoc ns-entity :namespace %))
               defs (bundle/defs-for-ns defs ns)]]
     (namespace-overview ns-url-fn mp-ns defs))])

(defn namespace-page [{:keys [ns-entity ns-data defs]}]
  (cljdoc.spec/assert :cljdoc.spec/namespace-entity ns-entity)
  (assert (platf/multiplatform? ns-data))
  (let [render-wiki-link (render-wiki-link-fn
                          (:namespace ns-entity)
                          #(routes/url-for :artifact/namespace :path-params (assoc ns-entity :namespace %)))]
    [:div.ns-page
     [:div.w-80-ns.pv4
      [:h2 (:namespace ns-entity)]
      (render-doc ns-data render-wiki-link)
      (if (seq defs)
        (for [adef defs]
          (def-block adef render-wiki-link))
        [:p.i.blue "No vars found in this namespace."])]]))

(comment
  (:platforms --d)

  (let [platforms (:platforms --d)]
    (< 1 (count (set (map :doc platforms)))))

  (platf/varies? --d :doc)
  (platf/get-field --d :name)

  )

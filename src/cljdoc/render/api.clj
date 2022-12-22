(ns cljdoc.render.api
  "Functions related to rendering API documenation"
  (:require [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.code-fmt :as code-fmt]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.util.ns-tree :as ns-tree]
            [cljdoc.bundle :as bundle]
            [cljdoc.platforms :as platf]
            [cljdoc.spec]
            [cljdoc.server.routes :as routes]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]))

(defn render-call-example
  [call]
  [:pre.ma0.pa0.pb3
   [:code.db {:class "language-clojure"} call]])

(defn render-call-example-bad-arglists
  [def-name bad-arglists]
  [:div
   [:pre.ma0.pa0.pb3
    [:code.db {:class "language-clojure"}
     (str "(" def-name " "  "??)")]]
   [:div.mb3.pa2.bg-washed-red.br2.f7.red
    [:span.b "?? invalid arglists:"]
    [:pre.ma0.pa0
     [:code (if (nil? bad-arglists)
              "nil"
              (str bad-arglists))]]]])

(defn parse-wiki-link [m]
  (if (string/includes? m "/")
    (let [[ns var] (string/split m #"/")]
      [ns var])
    ;; While in theory vars can contain dots it's fairly uncommon and
    ;; so we assume if there's no / and a dot that the user is
    ;; referring to a namespace
    (if (string/includes? m ".")
      [m nil]
      [nil m])))

(defn docstring->html [doc-str render-wiki-link fix-opts]
  [:div
   [:div.lh-copy.markdown.cljdoc-markup
    ;; If someone sets `{:doc false}`, there will be no docstring
    (when doc-str
      (-> doc-str
          (rich-text/markdown-to-html
           {:escape-html? true
            :render-wiki-link (comp render-wiki-link parse-wiki-link)})
          (fixref/fix fix-opts)
          hiccup/raw))]
   [:pre.lh-copy.bg-near-white.code.pa3.br2.f6.overflow-x-scroll.dn.raw
    doc-str]])

(defn valid-ref-pred-fn [{:keys [defs] :as _cache-bundle}]
  (fn [current-ns target-ns target-var]
    (let [target-ns (if target-ns (ns-tree/replant-ns current-ns target-ns) current-ns)]
      (if target-var
        (some #(and (= target-var (:name %))
                    (= target-ns (:namespace %)))
              defs)
        (some #(= target-ns (:namespace %)) defs)))))

(defn render-wiki-link-fn
  "Given the `current-ns` and a function `ns-link-fn` that is assumed
  to return a link to a passed namespace, return a function that receives
  a `[ns var]` tuple and will return a Markdown link to the specified ns/var."
  [current-ns valid-ref-pred ns-link-fn]
  (fn render-wiki-link-inner [[ns var]]
    (when (valid-ref-pred current-ns ns var)
      (str (ns-link-fn (if ns (ns-tree/replant-ns current-ns ns) current-ns))
           (when var (str "#" var))))))

(defn- varies-for-platforms?
  "Does element vary, for rendering purposes, across platforms?

  Note: from an API perspective, src-uri is, I think, uninteresting to note as a variation."
  [d]
  (or (platf/varies? d :members)
      (platf/varies? d :doc)
      (platf/varies? d :arglists)))

(defn- platforms->short-text [platforms]
  (case platforms
    #{"clj" "cljs"} "clj/s"
    #{"clj"}        "clj"
    #{"cljs"}       "cljs"))

(defn- platforms->long-text [platforms]
  (case platforms
    #{"clj" "cljs"} "All platforms"
    #{"clj"}        "Clojure"
    #{"cljs"}       "ClojureScript"))

(defn- platforms->var-annotation [d]
  (let [platforms (platf/platforms d)
        text (platforms->short-text platforms)]
    (if (varies-for-platforms? d)
      ;; for vars we indicate when the definition varies for platforms
      "clj/s≠"
      text)))

(defn render-var-annotation [annotation]
  (when annotation
    [:sup.f7.fw2.gray.ml1 annotation]))

(defn render-platform-specific [platform content]
  [:div.relative
   [:div.f7.gray.dib.w2.v-top.mt1 platform]
   [:div.dib content]])

(defn render-ns-docs
  "Render docstring for ns `n` distinguishing platform differences, if any."
  [n render-wiki-link fix-opts]
  (let [render-docs #(docstring->html % render-wiki-link fix-opts)]
    (if (platf/varies? n :doc)
      (for [p (sort (platf/platforms n))
            :let [doc (platf/get-field n :doc p)]
            :when doc]
        (render-platform-specific p (render-docs doc)))
      (render-docs (platf/get-field n :doc)))))

(defn- looks-like-arglists? [x]
  (and (sequential? x)
       (sequential? (first x))))

(defn- to-call-examples
  "Returns formatted call examples for `def-name` and `arglists`,
  or `nil` when arglists fail to format (and therefore likely invalid Clojure)"
  [def-name arglists]
  (when (looks-like-arglists? arglists)
    (try
      (doall
       (for [argv (sort-by count arglists)]
         (-> (str "("
                  def-name
                  (when (seq argv) " ") (string/join " " argv)
                  ")")
             code-fmt/snippet)))
      (catch Throwable _ex))))

(defn render-arglists [def-name arglists]
  (if-let [calls (to-call-examples def-name arglists)]
    (for [c calls]
      (render-call-example c))
    (render-call-example-bad-arglists def-name arglists)))

(defn render-var-args-and-docs
  "Render arglists and docstring for var `d` distinguishing platform differences, if any."
  [d render-wiki-link fix-opts]
  (let [def-name (platf/get-field d :name)
        fpdoc #(platf/get-field d :doc %)
        fpargs #(platf/get-field d :arglists %)
        fdoc #(platf/get-field d :doc)
        fargs #(platf/get-field d :arglists)
        render-args #(render-arglists def-name %)
        render-docs #(docstring->html % render-wiki-link fix-opts)
        platforms (sort (platf/platforms d))]
    (cond
      (and (platf/varies? d :arglists) (platf/varies? d :doc))
      (for [p platforms]
        (render-platform-specific p [:div
                                     (render-args (fpargs p))
                                     (render-docs (fpdoc p))]))

      (platf/varies? d :arglists)
      [:div
       (for [p platforms]
         (render-platform-specific p (render-args (fpargs p))))
       (render-docs (fdoc))]

      (platf/varies? d :doc)
      [:div
       (render-args (fargs))
       (for [p (sort (platf/platforms d))
             :let [doc (fpdoc p)]
             :when doc]
         (render-platform-specific p (render-docs doc)))]

      :else
      [:div
       (render-args (fargs))
       (render-docs (fdoc))])))

(defn- render-protocol-members [def render-wiki-link fix-opts]
  (let [members (platf/get-field def :members)]
    (when (seq members)
      [:div.pl3.bl.b--black-10
       (for [m members]
         [:div.bb.b--black-10.mb1
          [:h4.def-block-title.mv0.pt2.pb3
           (platf/get-field m :name)
           (render-var-annotation (platforms->var-annotation m))]
          (render-var-args-and-docs m render-wiki-link fix-opts)])])))

(defn def-block
  [def render-wiki-link fix-opts]
  {:pre [(platf/multiplatform? def)]}
  (let [def-name (platf/get-field def :name)]
    [:div.def-block
     [:hr.mv3.b--black-10]
     [:h4.def-block-title.mv0.pv3
      {:name (platf/get-field def :name), :id def-name}
      def-name (render-var-annotation (platforms->var-annotation def))
      (when-not (= :var (platf/get-field def :type))
        [:span.f7.ttu.normal.gray.ml2 (platf/get-field def :type)])
      (when (platf/get-field def :deprecated)
        [:span.fw3.f6.light-red.ml2 "deprecated"])]
     (render-var-args-and-docs def render-wiki-link fix-opts)
     (render-protocol-members def render-wiki-link fix-opts)
     (when (seq (platf/get-field def :members))
       [:div.lh-copy.pl3.bl.b--black-10
        (for [m (platf/get-field def :members)]
          [:div
           [:h5 (:name m)]
           (render-arglists (:name m) (:arglists m))
           (when (:doc m)
             [:p (docstring->html (:doc m) render-wiki-link fix-opts)])])])
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
                               (not= (:artifact-id version-entity) (:artifact-id ns-entity))))]
    [:div
     [:ul.list.pl0
      (for [[ns level _ _leaf?] (ns-tree/namespace-hierarchy (keys keyed-namespaces))
            :let [style {:margin-left (str (* (dec level) 10) "px")}
                  nse (get keyed-namespaces ns)]]
        [:li
         [:a.link.hover-dark-blue.blue.dib.pv1
          {:href (routes/url-for :artifact/namespace :path-params (assoc version-entity :namespace ns))
           :class (when (= ns current) "b")
           :style style}
          (->> (ns-tree/split-ns ns)
               (drop (dec level)))
          (when (and nse (from-dependency? nse))
            [:sup.pl1.normal "†"])]])]
     (when (some from-dependency? namespaces)
       [:p.f7.fw5.gray.mt4 [:sup.f6.db "†"] "Included via a transitive dependency."])]))

(defn platform-stats [defs]
  (let [grouped-by-platform-support (->> defs
                                         (map platf/platforms)
                                         (group-by identity))
        counts-by-platform (-> grouped-by-platform-support
                               (update #{"clj"} count)
                               (update #{"cljs"} count)
                               (update #{"clj" "cljs"} count))]
    (->> counts-by-platform (sort-by val) reverse (filter (comp pos? second)))))

(defn platforms-supported-note [[[dominant-platf] :as platf-stats]]
  [:span.f7.fw5.gray
   (if (= 1 (count platf-stats))
     (let [text (platforms->long-text dominant-platf)]
       ;; we use long text when all vars share same platforms
       (if (= 1 (count dominant-platf))
         (str text " only.")
         (str text ".")))
     (list
       ;; we use short form here to match annotations we will be using on vars
      (str "Mostly " (platforms->short-text dominant-platf) ".")
      [:br] " Exceptions indicated."))])

(defn var-index-platform-annotation [ns-dominant-platform d]
  (when (or (not= (platf/platforms d) ns-dominant-platform)
            ;; always show annotation when platforms vary for def
            (varies-for-platforms? d))
    (platforms->var-annotation d)))

(defn definitions-list [_ns-entity defs {:keys [indicate-platforms-other-than]}]
  [:div.pb4
   [:ul.list.pl0
    (for [def defs
          :let [def-name (platf/get-field def :name)]]
      [:li.def-item
       [:a.link.dim.blue.dib.pa1.pl0 {:href (str "#" def-name)} def-name
        (render-var-annotation (var-index-platform-annotation indicate-platforms-other-than def))]])]])

(defn namespace-overview
  [ns-url-fn mp-ns defs valid-ref-pred fix-opts]
  {:pre [(platf/multiplatform? mp-ns) (fn? ns-url-fn)]}
  (let [ns-name (platf/get-field mp-ns :name)]
    [:div
     [:a.link.black
      {:href (ns-url-fn ns-name)}
      [:h2
       {:data-cljdoc-type "namespace"}
       ns-name
       [:img.ml2 {:src "https://microicon-clone.vercel.app/chevron/12/357edd"}]]]
     (render-ns-docs mp-ns
                     (render-wiki-link-fn ns-name valid-ref-pred ns-url-fn)
                     fix-opts)
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
  [{:keys [ns-entity namespaces defs valid-ref-pred fix-opts]}]
  [:div.mw7.center.pv4
   (for [mp-ns (->> namespaces
                    (filter #(string/starts-with? (platf/get-field % :name) (:namespace ns-entity))))
         :let [ns (platf/get-field mp-ns :name)
               ns-url-fn #(routes/url-for :artifact/namespace :path-params (assoc ns-entity :namespace %))
               defs (bundle/defs-for-ns defs ns)]]
     (namespace-overview ns-url-fn mp-ns defs valid-ref-pred fix-opts))])

(defn namespace-page [{:keys [ns-entity ns-data defs valid-ref-pred fix-opts]}]
  (cljdoc.spec/assert :cljdoc.spec/namespace-entity ns-entity)
  (assert (platf/multiplatform? ns-data))
  (let [render-wiki-link (render-wiki-link-fn
                          (:namespace ns-entity)
                          valid-ref-pred
                          #(routes/url-for :artifact/namespace :path-params (assoc ns-entity :namespace %)))]
    [:div.ns-page
     [:div.w-80-ns.pv4
      [:h2 (:namespace ns-entity)]
      (render-ns-docs ns-data render-wiki-link fix-opts)
      (if (seq defs)
        (for [adef defs]
          (def-block adef render-wiki-link fix-opts))
        [:p.i.blue "No vars found in this namespace."])]]))

(comment
  (:platforms --d)

  (routes/url-for :artifact/namespace :path-params {:group-id "grp" :artifact-id "art" :version "ver" :namespace "foo boo loo"})

  (let [platforms (:platforms --d)]
    (< 1 (count (set (map :doc platforms)))))

  (platf/varies? --d :doc)
  (platf/get-field --d :name))

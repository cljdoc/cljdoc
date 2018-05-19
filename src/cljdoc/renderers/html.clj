(ns cljdoc.renderers.html
  (:require [cljdoc.routes :as r]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.renderers.markup :as markup]
            [cljdoc.util.ns-tree :as ns-tree]
            [cljdoc.util]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.cache]
            [cljdoc.spec]
            [hiccup2.core :as hiccup]
            [hiccup.page]
            [aleph.http]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(def github-url "https://github.com/martinklepsch/cljdoc/issues")

(defn page [opts contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {}
                [:head
                 [:title (:title opts)]
                 [:meta {:charset "utf-8"}]
                 (hiccup.page/include-css
                   "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"
                   "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/styles/github-gist.min.css"
                   "/cljdoc.css")]
                [:div.sans-serif
                 contents]
                (hiccup.page/include-js
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js"
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure.min.js"
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure-repl.min.js"
                  "/cljdoc.js")
                [:script "hljs.initHighlightingOnLoad();"]]))

(defn sidebar-title [title]
  [:h4.ttu.f7.fw5.tracked.gray title])

(defn clojars-id [{:keys [group-id artifact-id] :as cache-id}]
  (if (= group-id artifact-id)
    artifact-id
    (str group-id "/" artifact-id)))

(def TOP-BAR-HEIGHT "57px")

(defn top-bar [cache-id version-meta]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a.dib.v-mid.link.dim.black.b.f6.mr3 {:href (r/path-for :artifact/version cache-id)}
    (clojars-id cache-id)]
   [:a.dib.v-mid.link.dim.gray.f6.mr3
    {:href (r/path-for :artifact/index cache-id)}
    (:version cache-id)]
   [:span.dib.v-mid.mr3.pv1.ph2.ba.b--moon-gray.br1.ttu.fw5.f7.gray.tracked "Non official"]
   [:a.black.no-underline.ttu.fw5.f7.tracked.pv1.ph2.dim
    {:href github-url}
    "Help Develop cljdoc"]
   [:div.tr
    {:style {:flex-grow 1}}
    [:a.link.dim.gray.f6.tr
     {:href (-> version-meta :scm :url)}
     [:img.v-mid.mr2 {:src "https://icon.now.sh/github"}]
     [:span.dib (if-let [scm-url (-> version-meta :scm :url)]
                  (subs scm-url 19)
                  (log/error "SCM Url missing from version-meta" (:scm version-meta)))]]]])

(defn def-code-block
  [content]
  [:pre
   [:code.db.mb2 {:class "language-clojure"}
    content]])

(defn def-block [platforms]
  (assert (coll? platforms) "def meta is not a map")
  ;; Currently we just render any platform, this obviously
  ;; isn't the best we can do
  (let [def-meta (first (sort-by :platform platforms))]
    (cljdoc.spec/assert :cljdoc.spec/def-full def-meta)
    [:div.def-block
     [:hr.mv3.b--black-10]
     [:h4.def-block-title
      {:name (:name def-meta), :id (:name def-meta)}
      (:name def-meta)
      (when-not (= :var (:type def-meta))
        [:span.f7.ttu.normal.gray.ml2 (:type def-meta)])
      (when (:deprecated def-meta) [:span.fw3.f6.light-red.ml2 "deprecated"])]
     ;; (when-not (= :var (:type def-meta))
     ;;   [:code (pr-str def-meta)])
     [:div.lh-copy
      (for [argv (sort-by count (:arglists def-meta))]
        (def-code-block (str "(" (:name def-meta) (when (seq argv) " ") (clojure.string/join " " argv) ")")))]
     (when (:doc def-meta)
       [:div.lh-copy.markdown
        (-> (:doc def-meta) markup/markdown-to-html hiccup/raw)])
     (when (seq (:members def-meta))
       [:div.lh-copy.pl3.bl.b--black-10
        (for [m (:members def-meta)]
          [:div
           [:h5 (:name m)]
           (for [argv (sort-by count (:arglists m))]
             (def-code-block (str "(" (:name m) " " (clojure.string/join " " argv) ")")))
           (when (:doc m)
             [:p (:doc m)])])])]))

(defn namespace-list [{:keys [current]} namespaces]
  (let [namespaces (ns-tree/index-by :namespace namespaces)]
    [:div
     (sidebar-title "Namespaces")
     [:ul.list.pl2
      (for [[ns level _ leaf?] (ns-tree/namespace-hierarchy (keys namespaces))]
        (if-let [ns (get namespaces ns)]
          [:li
           [:a.link.dim.blue.dib.pa1
            {:href (r/path-for :artifact/namespace ns)
             :class (str (when (= (:namespace ns) current) "b")
                         " "
                         (case level
                           1 ""
                           2 "pl3"
                           3 "pl4"
                           4 "pl5"))}

            (->> (ns-tree/split-ns (:namespace ns))
                 (drop (dec level)))]]
          [:li.blue.pa1 ns]))

      #_(for [ns (sort-by :namespace namespaces)]
          [:li
           [:a.link.dim.blue.dib.pa1
            {:href (r/path-for :artifact/namespace ns)
             :class (when (= (:namespace ns) current) "b")}
            (:namespace ns)]])]]))

(defn article-list [doc-tree]
  [:div
   (sidebar-title "Articles")
   doc-tree])

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
                                         (map #(select-keys % [:name :platform]))
                                         (group-by :name)
                                         vals
                                         (map (fn [defs]
                                                (set (map :platform defs))))
                                         (group-by identity))
        counts-by-platform (-> grouped-by-platform-support
                               (update #{"clj"} count)
                               (update #{"cljs"} count)
                               (update #{"clj" "cljs"} count))]
    (->> counts-by-platform (sort-by val) reverse (filter (comp pos? second)))))

(defn definitions-list [ns-entity defs {:keys [indicate-platforms-other-than]}]
  [:div.pb4
   [:ul.list.pl0
    (for [[def-name platf-defs] (->> defs
                                     (group-by :name)
                                     (sort-by key))]
      [:li.def-item
       [:a.link.dim.blue.dib.pa1.pl0
        {:href (r/path-for :artifact/def (merge ns-entity {:def def-name}))}
        def-name]
       (when-not (= (set (map :platform platf-defs))
                    indicate-platforms-other-than)
         [:sup.f7.gray
          (-> (set (map :platform platf-defs))
              (humanize-supported-platforms))])])]])

(defn sidebar [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10
   {:style {:top TOP-BAR-HEIGHT}} ; CSS HACK
   contents])

(defn sidebar-two [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10.sidebar-scroll-view
   {:style {:top TOP-BAR-HEIGHT :left "16rem"}} ; CSS HACK
   contents])

(defn index-page [{:keys [top-bar-component doc-tree-component namespace-list-component]}]
  [:div
   top-bar-component
   (sidebar
    (article-list doc-tree-component)
    namespace-list-component)])

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

(defn main-container [{:keys [offset]} & content]
   [:div.absolute.bottom-0.right-0
    {:style {:left offset :top TOP-BAR-HEIGHT}}
    (into [:div.absolute.top-0.bottom-0.left-0.right-0.overflow-y-scroll.ph4-ns.ph2.main-scroll-view]
          content)])

(defn namespace-page [{:keys [ns-entity ns-data defs top-bar-component doc-tree-component namespace-list-component]}]
  (cljdoc.spec/assert :cljdoc.spec/namespace-entity ns-entity)
  (let [sorted-defs                        (sort-by :name defs)
        [[dominant-platf] :as platf-stats] (platform-stats defs)]
    [:div.ns-page
     top-bar-component
     (sidebar
      (article-list doc-tree-component)
      namespace-list-component)
     (sidebar-two
      (platform-support-note platf-stats)
      (definitions-list ns-entity sorted-defs
        {:indicate-platforms-other-than dominant-platf}))
     (main-container
      {:offset "32rem"}
      [:div.w-80-ns.pv4
       [:h2 (:namespace ns-entity)]
       (when-let [ns-doc (:doc ns-data)]
         [:div.lh-copy.markdown
          (-> ns-doc markup/markdown-to-html hiccup/raw)])
       (for [[def-name platf-defs] (->> defs
                                        (group-by :name)
                                        (sort-by key))]
         (def-block platf-defs))])]))

(defn doc-link [cache-id slugs]
  (assert (seq slugs) "Slug path missing")
  (->> (clojure.string/join "/" slugs)
       (assoc cache-id :doc-page)
       (r/path-for :artifact/doc)))

(defn subseq? [a b]
  (= (take (count b) a) b))

(defn doc-tree-view
  [cache-id doc-bundle current-page]
  (->> doc-bundle
       (map (fn [doc-page]
              (let [slug-path (-> doc-page :attrs :slug-path)]
                [:li
                 [:a.link.blue.dib.pa1
                  {:href  (doc-link cache-id slug-path)
                   :class (if (= current-page slug-path) "fw7" "link dim")}
                  (:title doc-page)]
                 (when (subseq? current-page slug-path)
                   (doc-tree-view cache-id (:children doc-page) current-page))])))
       (into [:ul.list.pl2])))

(defn doc-page [{:keys [top-bar-component
                        doc-tree-component
                        namespace-list-component
                        doc-html] :as args}]
  [:div
   top-bar-component
   (sidebar
    (article-list doc-tree-component)
    namespace-list-component)
   (main-container
    {:offset "16rem"}
    [:div.mw7.center
     ;; TODO dispatch on a type parameter that becomes part of the attrs map
     (if doc-html
       [:div.markdown.lh-copy.pv4 (hiccup/raw doc-html)]
       [:div.lh-copy.pv6.tc
        #_[:pre (pr-str (dissoc args :top-bar-component :doc-tree-component :namespace-list-component))]
        [:span.f4.serif.gray.i "Space intentionally left blank."]])])])

(defn render-to [opts hiccup ^java.io.File file]
  (log/info "Writing" (clojure.string/replace (.getPath file) #"^.+grimoire-html" "grimoire-html"))
  (->> hiccup (page opts) str (spit file)))

(defn file-for [out-dir route-id route-params]
  (doto (io/file out-dir (subs (r/path-for route-id route-params) 1) "index.html")
    io/make-parents))

(defmulti render (fn [page-type route-params cache-bundle] page-type))

(defmethod render :default
  [page-type _ _]
  (format "%s not implemented, sorry" page-type))

(defmethod render :artifact/index
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (let [artifact-id (:artifact-id route-params)
        artifact-entity (assoc cache-id :artifact-id artifact-id)
        big-btn-link :a.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div.pa4-ns.pa2
          [:h1 (clojars-id artifact-entity)]
          [:span.db "Known versions on cljdoc:"]
          [:ol.list.pl0.pv3
           (for [v (->> (:versions cache-contents)
                        (filter #(= (:artifact-id %) (:artifact-id route-params)))
                        (sort-by :version))]
             [:li.dib.mr3
              [big-btn-link
               {:href (r/path-for :artifact/version (merge cache-id v))}
               (:version v)]])]
          (when-not (= #{artifact-id} (set(:artifacts cache-contents)))
            [:div
             [:h3 "Other artifacts under the " (:group-id cache-id) " group"]
             [:ol.list.pl0.pv3
              (for [a (sort (:artifacts cache-contents))]
                [:li.dib.mr3
                 [big-btn-link
                  {:href (r/path-for :artifact/index (assoc cache-id :artifact-id a))}
                  a]])]])]
         (page {:title (str (clojars-id artifact-entity) " — cljdoc")}))))

(defmethod render :artifact/version
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (->> (index-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                    :doc-tree-component (doc-tree-view cache-id
                                                       (doctree/add-slug-path (-> cache-contents :version :doc))
                                                       [])
                    :namespace-list-component (namespace-list
                                               {}
                                               (cljdoc.cache/namespaces cache-bundle))})
       (page {:title (str (clojars-id cache-id) " " (:version cache-id))})))

(defmethod render :artifact/doc
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:doc-slug-path route-params))
  (let [doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> cache-contents :version :doc))
        doc-p (->> doc-tree
                   doctree/flatten*
                   (filter #(= doc-slug-path (:slug-path (:attrs %))))
                   first)
        doc-p-html (or (when-let [md (some-> doc-p :attrs :cljdoc/markdown)]
                         (fixref/fix (-> doc-page :attrs :cljdoc.doc/source-file)
                                     (-> md markup/markdown-to-html)
                                     {:scm (:scm (:version cache-contents))}))
                       (when-let [adoc (some-> doc-p :attrs :cljdoc/asciidoc)]
                         (-> adoc markup/asciidoc-to-html)))]
    (->> (doc-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                    :doc-tree-component (doc-tree-view cache-id doc-tree doc-slug-path)
                    :namespace-list-component (namespace-list
                                               {}
                                               (cljdoc.cache/namespaces cache-bundle))
                    :doc-html doc-p-html})
         (page {:title (str (:title doc-p) " — " (clojars-id cache-id) " " (:version cache-id))}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:namespace route-params))
  (let [ns-emap route-params
        defs (filter #(= (:namespace ns-emap) (:namespace %)) (:defs cache-contents))
        ns-data (first (filter #(= (:namespace ns-emap) (:name %))
                               (:namespaces cache-contents)))]
    (when (empty? defs)
      (log/warnf "Namespace %s contains no defs" (:namespace route-params)))
    (->> (namespace-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                          :doc-tree-component (doc-tree-view cache-id
                                                             (doctree/add-slug-path (-> cache-contents :version :doc))
                                                             [])
                          :namespace-list-component (namespace-list
                                                     {:current (:namespace ns-emap)}
                                                     (cljdoc.cache/namespaces cache-bundle))
                          :ns-entity ns-emap
                          :ns-data ns-data
                          :defs defs})
         (page {:title (str (:namespace ns-emap) " — " (clojars-id cache-id) " " (:version cache-id))}))))

(defn write-docs* [{:keys [cache-contents cache-id] :as cache-bundle} ^java.io.File out-dir]
  (let [top-bar-comp (top-bar cache-id (:version cache-contents))
        doc-tree     (doctree/add-slug-path (-> cache-contents :version :doc))]

    ;; Index page for given version
    (log/info "Rendering index page for" cache-id)
    (->> (str (render :artifact/version {} cache-bundle))
         (spit (file-for out-dir :artifact/version cache-id)))

    ;; Documentation Pages / Articles
    (doseq [doc-p (-> doc-tree doctree/flatten*)]
      (log/info "Rendering Doc Page" (dissoc doc-p :attrs))
      (->> (str (render :artifact/doc {:doc-slug-path (:slug-path (:attrs doc-p))} cache-bundle))
           (spit (->> (-> doc-p :attrs :slug-path)
                      (clojure.string/join "/")
                      (assoc cache-id :doc-page)
                      (file-for out-dir :artifact/doc)))))

    ;; Namespace Pages
    (doseq [ns-emap (cljdoc.cache/namespaces cache-bundle)
            :let [defs (filter #(= (:namespace ns-emap)
                                   (:namespace %))
                               (:defs cache-contents))]]
      (log/infof "Rendering namespace %s" (:namespace ns-emap))
      (->> (str (render :artifact/namespace ns-emap cache-bundle))
           (spit (file-for out-dir :artifact/namespace ns-emap))))))

(defrecord HTMLRenderer []
  cljdoc.cache/ICacheRenderer
  (render [_ cache-bundle {:keys [dir] :as out-cfg}]
    (cljdoc.spec/assert :cljdoc.spec/cache-bundle cache-bundle)
    (assert (and dir (.isDirectory dir)) (format "HTMLRenderer expects output directory, was %s" dir))
    (write-docs* cache-bundle dir)))

(defn- on-clojars? [coordinate]
  ;; TODO move elsewhere as this won't load in analysis env
  (try
    (= 200 (:status @(aleph.http/head (cljdoc.util/remote-jar-file coordinate))))
    (catch clojure.lang.ExceptionInfo e
      false)))

(defn request-build-page [route-params]
  (->> [:div.pa4-ns.pa2
        [:h1 "Want to build some documentation?"]
        [:p "We currently don't have documentation built for " (clojars-id route-params) " v" (:version route-params)]
        (if (on-clojars? [(clojars-id route-params) (:version route-params)])
          [:form.pv3 {:action "/api/request-build2" :method "POST"}
           [:input.pa2.mr2.br2.ba.outline-0.blue {:type "text" :id "project" :name "project" :value (str (:group-id route-params) "/" (:artifact-id route-params))}]
           [:input.pa2.mr2.br2.ba.outline-0.blue {:type "text" :id "version" :name "version" :value (:version route-params)}]
           [:input.ph3.pv2.mr2.br2.ba.b--blue.bg-white.blue.ttu.pointer.b {:type "submit" :value "build"}]]
          [:div
           [:p "We also can't find it on Clojars, which at this time, is required to build documentation."]
           [:p [:a.no-underline.blue {:href github-url} "Let us know if this is unexpected."]]])]
       (page {:title (str "Build docs for " (clojars-id route-params))})))

(defn build-submitted-page [circle-job-url]
  (->> [:div.pa4-ns.pa2
        [:h1 "Thanks for using cljdoc!"]
        [:p "Your documentation build is " [:a.link.blue.no-underline {:href circle-job-url} "in progress"]]
        [:p "If anything isn't working as you'd expect, please " [:a.no-underline.blue {:href github-url} "reach out."]]]
       (page {:title "Build submitted"})))

(defn local-build-submitted-page []
  (->> [:div.pa4-ns.pa2
        [:h1 "The build has been submitted to the local analysis service"]
        [:p "Check the logs to see how it's progressing. Errors will be logged there too."]
        [:div.markdown
         [:pre [:code "tail -f log/cljdoc.log"]]]
        [:p "If anything isn't working as you'd expect, please " [:a.no-underline.blue {:href github-url} "reach out."]]]
       (page {:title "Build submitted"})))

(comment


  (write-docs store platf out)

  (defn namespace-hierarchy [ns-list]
    (reduce (fn [hierarchy ns-string]
              (let [ns-path (clojure.string/split ns-string #"\.")]
                (if (get-in hierarchy ns-path)
                  hierarchy
                  (assoc-in hierarchy ns-path nil))))
            {}
            ns-list))

  (namespace-hierarchy (map :name namespaces))

  (-> (doctree/add-slug-path (-> (:cache-contents cljdoc.cache/cache) :version :doc))
      first)

  (r/path-for :artifact/doc {:group-id "a" :artifact-id "b" :version "v" :doc-page "asd/as"})

  )

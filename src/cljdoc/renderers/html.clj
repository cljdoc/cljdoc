(ns cljdoc.renderers.html
  (:require [cljdoc.routes :as r]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.common :as common]
            [cljdoc.render.api :as api]
            [cljdoc.util]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.cache]
            [cljdoc.spec]
            [hiccup2.core :as hiccup]
            [hiccup.page]
            [aleph.http]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]))

(defn clojars-id [{:keys [group-id artifact-id] :as cache-id}]
  (if (= group-id artifact-id)
    artifact-id
    (str group-id "/" artifact-id)))

(defn top-bar [cache-id version-meta]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a.dib.v-mid.link.dim.black.b.f6.mr3 {:href (r/path-for :artifact/version cache-id)}
    (clojars-id cache-id)]
   [:a.dib.v-mid.link.dim.gray.f6.mr3
    {:href (r/path-for :artifact/index cache-id)}
    (:version cache-id)]
   [:a {:href "/"}
    [:span.link.dib.v-mid.mr3.pv1.ph2.ba.hover-blue.br1.ttu.fw5.f7.silver.tracked "cljdoc Alpha"]]
   [:a.silver.link.hover-blue.ttu.fw5.f7.tracked.pv1
    {:href (common/github-url :issues)}
    "Have Feedback?"]
   [:div.tr
    {:style {:flex-grow 1}}
    [:form.dib.mr3 {:action "/api/request-build2" :method "POST"}
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "project" :name "project" :value (str (:group-id cache-id) "/" (:artifact-id cache-id))}]
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "version" :name "version" :value (:version cache-id)}]
     [:input.f7.white.hover-near-white.outline-0.bn.bg-white {:type "submit" :value "rebuild"}]]
    (if-let [scm-url (-> version-meta :scm :url)]
      [:a.link.dim.gray.f6.tr
       {:href scm-url}
       [:img.v-mid.mr2 {:src "https://icon.now.sh/github"}]
       [:span.dib (cljdoc.util/gh-coordinate scm-url)]]
      [:a.f6.link.blue {:href (common/github-url :userguide/scm-faq)} "SCM info missing"])]])

(defn article-list [doc-tree]
  [:div
   (layout/sidebar-title "Articles")
   (or doc-tree
       [:p.pl2.f7.gray [:a.blue.link {:href (common/github-url
       :userguide/articles)} "Articles"] " are a practical way to
       provide additional guidance beyond API documentation. To use
       them, please ensure you " [:a.blue.link {:href (common/github-url
       :userguide/scm-faq)} "properly set SCM info"] " in your
       project."])])

(defn index-page [{:keys [top-bar-component doc-tree-component namespace-list-component]}]
  [:div
   top-bar-component
   (layout/sidebar
    (article-list doc-tree-component)
    namespace-list-component)])

(defn doc-link [cache-id slugs]
  (assert (seq slugs) "Slug path missing")
  (->> (clojure.string/join "/" slugs)
       (assoc cache-id :doc-page)
       (r/path-for :artifact/doc)))

(defn subseq? [a b]
  (= (take (count b) a) b))

(defn doc-tree-view
  [cache-id doc-bundle current-page]
  (when (seq doc-bundle)
    (->> doc-bundle
         (map (fn [doc-page]
                (let [slug-path (-> doc-page :attrs :slug-path)]
                  [:li
                   [:a.link.blue.dib.pa1
                    {:href  (doc-link cache-id slug-path)
                     :class (if (= current-page slug-path) "fw7" "link dim")}
                    (:title doc-page)]
                   (doc-tree-view cache-id (:children doc-page) current-page)])))
         (into [:ul.list.pl2]))))

(defn doc-page [{:keys [top-bar-component
                        doc-tree-component
                        namespace-list-component
                        doc-html] :as args}]
  [:div
   top-bar-component
   (layout/sidebar
    (article-list doc-tree-component)
    namespace-list-component)
   (layout/main-container
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
  (->> hiccup (layout/page opts) str (spit file)))

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
         (layout/page {:title (str (clojars-id artifact-entity) " — cljdoc")}))))

(defmethod render :artifact/version
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (->> (index-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                    :doc-tree-component (doc-tree-view cache-id
                                                       (doctree/add-slug-path (-> cache-contents :version :doc))
                                                       [])
                    :namespace-list-component (api/namespace-list
                                               {}
                                               (cljdoc.cache/namespaces cache-bundle))})
       (layout/page {:title (str (clojars-id cache-id) " " (:version cache-id))})))

(defmethod render :artifact/doc
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:doc-slug-path route-params))
  (let [doc-slug-path (:doc-slug-path route-params)
        doc-tree (doctree/add-slug-path (-> cache-contents :version :doc))
        doc-p (->> doc-tree
                   doctree/flatten*
                   (filter #(= doc-slug-path (:slug-path (:attrs %))))
                   first)
        doc-html (or (some-> doc-p :attrs :cljdoc/markdown rich-text/markdown-to-html)
                     (some-> doc-p :attrs :cljdoc/asciidoc rich-text/asciidoc-to-html))
        fixed-html (fixref/fix (-> doc-p :attrs :cljdoc.doc/source-file)
                               doc-html
                               {:scm (:scm (:version cache-contents))
                                :artifact-entity cache-id
                                :flattened-doctree (doctree/flatten* doc-tree)})]
    (->> (doc-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                    :doc-tree-component (doc-tree-view cache-id doc-tree doc-slug-path)
                    :namespace-list-component (api/namespace-list
                                               {}
                                               (cljdoc.cache/namespaces cache-bundle))
                    :doc-html fixed-html})
         (layout/page {:title (str (:title doc-p) " — " (clojars-id cache-id) " " (:version cache-id))}))))

(defmethod render :artifact/namespace
  [_ route-params {:keys [cache-id cache-contents] :as cache-bundle}]
  (assert (:namespace route-params))
  (let [ns-emap route-params
        defs (filter #(= (:namespace ns-emap) (:namespace %)) (:defs cache-contents))
        ns-data (first (filter #(= (:namespace ns-emap) (:name %))
                               (:namespaces cache-contents)))]
    (when (empty? defs)
      (log/warnf "Namespace %s contains no defs" (:namespace route-params)))
    (->> (api/namespace-page {:top-bar-component (top-bar cache-id (:version cache-contents))
                              :scm-info (:scm (:version cache-contents))
                              :article-list-component (article-list
                                                       (doc-tree-view cache-id
                                                                      (doctree/add-slug-path (-> cache-contents :version :doc))
                                                                      []))
                              :namespace-list-component (api/namespace-list
                                                         {:current (:namespace ns-emap)}
                                                         (cljdoc.cache/namespaces cache-bundle))
                              :ns-entity ns-emap
                              :ns-data ns-data
                              :defs defs})
         (layout/page {:title (str (:namespace ns-emap) " — " (clojars-id cache-id) " " (:version cache-id))}))))

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

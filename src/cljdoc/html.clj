(ns cljdoc.html
  (:require [cljdoc.routes :as r]
            [cljdoc.cache]
            [cljdoc.spec]
            [hiccup2.core :as hiccup]
            [hiccup.page]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]))

(defn page [contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {}
                [:head
                 [:link {:rel "stylesheet" :href "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"}]]
                [:div.sans-serif
                 contents]]))

(defn top-bar [cache-id]
  [:nav.pa3.pa4-ns.bb.b--black-10
   [:a.link.dim.black.b.f6.dib.mr3 {:href (r/path-for :artifact/version cache-id)}
    (str
     (:group-id cache-id) "/"
     (:artifact-id cache-id))]
   [:a.link.dim.gray.f6.dib
    {:href (r/path-for :artifact/index cache-id)}
    (:version cache-id)]
   [:a.link.dim.gray.f6.dib.fr {:href "#"} "github.com/not-done/yet"]])

(defn def-block [def-meta]
  (spec/assert :cljdoc.spec/def-full def-meta)
  (assert (map? def-meta) "def meta is not a map")
  [:div
   [:h4
    {:name (:name def-meta) :id (:name def-meta) }
    (:name def-meta)
    [:span.f5.gray.ml2 (:type def-meta)]
    (when (:deprecated def-meta) [:span.f6.light-red.ml2.ttu "deprecated"])
    ]
   [:div
    (for [argv (sort-by count (:arglists def-meta))]
      [:pre (str "(" (:name def-meta) " " (clojure.string/join " " argv) ")")])]
   [:p.lh-copy.w8 (:doc def-meta)]
   ;; source-fn really is broken
   ;; [:pre.pa3.bg-black-05.br2.overflow-scroll (:src def-meta)]
   ;; [:pre (with-out-str (clojure.pprint/pprint def-meta))]
   [:hr.b--black-10]])

(defn namespace-list [namespaces]
  [:div
   [:h4 "Namespaces"]
   [:ul.list.pl2
    (for [ns (sort-by :namespace namespaces)]
      [:li
       [:a.link.dim.blue.dib.pa1
        {:href (r/path-for :artifact/namespace ns)}
        (:namespace ns)]])]])

(defn article-list [articles]
  [:div
   [:h4 "Articles"]
   [:ul.list.pl2
    [:li
     [:a.no-underline.black.dib.pa1 {:href "#"} "Nothing there yet"]]]])

(defn humanize-supported-platforms
  ([supported-platforms]
   (humanize-supported-platforms supported-platforms :short))
  ([supported-platforms style]
   (case style
     :short (case supported-platforms
              #{"clj" "cljs"} "CLJ/S"
              #{"clj"}        "CLJ"
              #{"cljs"}       "CLJS")
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
  [:div.absolute.overflow-scroll.CSS_HACK
   {:style {:bottom "0px" :top "10rem"}} ; CSS HACK
   [:div.pb4
    [:ul.list.pl2
     (for [[def-name platf-defs] (->> defs
                                      (group-by :name)
                                      (sort-by key))]
       [:li
        [:a.link.dim.blue.dib.pa1
         {:href (r/path-for :artifact/def (merge ns-entity {:def def-name}))}
         def-name]
        (when-not (= (set (map :platform platf-defs))
                     indicate-platforms-other-than)
          [:span.f6.ttu.gray
           (-> (set (map :platform platf-defs))
               (humanize-supported-platforms))])])]]])


(defn sidebar [& contents]
  [:div.fixed.w5.pa3.pa4-ns.bottom-0.CSS_HACK
   {:style {:top "80px"}} ; CSS HACK
   contents])

(defn index-page [cache-id namespace-emaps]
  [:div
   (top-bar cache-id)
   (sidebar
    (article-list [])
    (namespace-list namespace-emaps))])

(defn platform-support-note [[[dominant-platf] :as platf-stats]]
  (if (= 1 (count platf-stats))
    (if (or (= dominant-platf #{"clj"})
            (= dominant-platf #{"cljs"}))
      [:span (str (humanize-supported-platforms dominant-platf :long) " only.")]
      [:span "All platforms support " (str (humanize-supported-platforms dominant-platf :long) ".")])
    [:span (str "Mostly " (humanize-supported-platforms dominant-platf) " forms. Exceptions indicated.")]))

(defn namespace-page [emap defs]
  (let [sorted-defs                        (sort-by :name defs)
        [[dominant-platf] :as platf-stats] (platform-stats defs)]
    [:div
     (top-bar emap)
     [:div
      (sidebar
       [:a.link.dim.blue.f6 {:href (r/path-for :artifact/version emap)} "All namespaces"]
       [:h3 (:namespace emap)]
       (platform-support-note platf-stats)
       (definitions-list emap sorted-defs
         {:indicate-platforms-other-than dominant-platf}))
      [:div.ml7.w-60-ns.pa4-ns.bl.b--black-10
       (map def-block sorted-defs)]]]))

(defn render-to [hiccup ^java.io.File file]
  (println "Writing" (clojure.string/replace (.getPath file) #"^.+grimoire-html" "grimoire-html"))
  (->> hiccup page str (spit file)))

(defn file-for [out-dir route-id route-params]
  (doto (io/file out-dir (subs (r/path-for route-id route-params) 1) "index.html")
    io/make-parents))

(defn write-docs* [{:keys [cache-contents cache-id]} ^java.io.File out-dir]
  (let [namespace-emaps (->> (:namespaces cache-contents)
                             (map :name)
                             (map #(merge cache-id {:namespace %}))
                             (map #(spec/assert :cljdoc.spec/namespace-entity %))
                             set)]
    (render-to (index-page cache-id namespace-emaps)
               (file-for out-dir :artifact/version cache-id))
    (doseq [ns-emap namespace-emaps
            :let [defs (filter #(= (:namespace ns-emap)
                                   (:namespace %))
                               (:defs cache-contents))]]
      (render-to (namespace-page ns-emap defs)
                 (file-for out-dir :artifact/namespace ns-emap)))))

(defrecord HTMLRenderer []
  cljdoc.cache/ICacheRenderer
  (render [_ cache-bundle {:keys [dir] :as out-cfg}]
    (spec/assert :cljdoc.spec/cache-bundle cache-bundle)
    (assert (and dir (.isDirectory dir) (nil? (:file out-cfg)) "HTMLRenderer expects output directory"))
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
  )

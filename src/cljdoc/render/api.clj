(ns cljdoc.render.api
  "Functions related to rendering API documenation"
  (:require [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.util.ns-tree :as ns-tree]
            [cljdoc.util.fixref :as fixref]
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

(defn render-doc-string [doc-str]
  [:div.lh-copy.markdown
   (-> doc-str
       (string/replace #"\[\[(.*)\]\]" "[`$1`](#$1)")
       (rich-text/markdown-to-html {:escape-html? true})
       hiccup/raw)])

(defn def-block [platforms src-uri]
  (assert (coll? platforms) "def meta is not a map")
  ;; Currently we just render any platform, this obviously
  ;; isn't the best we can do PLATF_SUPPORT
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
        (def-code-block 
          (str "(" (:name def-meta) (when (seq argv) " ") (string/join " " argv) ")")))]
     (some-> def-meta :doc render-doc-string)
     (when (seq (:members def-meta))
       [:div.lh-copy.pl3.bl.b--black-10
        (for [m (:members def-meta)]
          [:div
           [:h5 (:name m)]
           (for [argv (sort-by count (:arglists m))]
             (def-code-block (str "(" (:name m) " " (string/join " " argv) ")")))
           (when (:doc m)
             [:p (:doc m)])])])
     (when src-uri
       [:a.link.f7.gray.hover-dark-gray {:href src-uri} "source"])]))

(defn namespace-list [{:keys [current]} namespaces]
  (let [base-params (select-keys (first namespaces) [:group-id :artifact-id :version])
        namespaces  (ns-tree/index-by :namespace namespaces)]
    [:div
     (layout/sidebar-title "Namespaces")
     [:ul.list.pl2
      (for [[ns level _ leaf?] (ns-tree/namespace-hierarchy (keys namespaces))
            :let [style {:margin-left (str (* (dec level) 10) "px")}]]
        [:li
         [:a.link.hover-dark-blue.blue.dib.pa1
          {:href (routes/url-for :artifact/namespace :path-params (assoc base-params :namespace ns))
           :class (when (= ns current) "b")
           :style style}
          (->> (ns-tree/split-ns ns)
               (drop (dec level)))]])

      #_(for [ns (sort-by :namespace namespaces)]
          [:li
           [:a.link.dim.blue.dib.pa1
            {:href (routes/url-for :artifact/namespace :path-params ns)
             :class (when (= (:namespace ns) current) "b")}
            (:namespace ns)]])]]))

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

(defn definitions-list [ns-entity defs {:keys [indicate-platforms-other-than]}]
  [:div.pb4
   [:ul.list.pl0
    (for [[def-name platf-defs] (->> defs
                                     (group-by :name)
                                     (sort-by key))]
      [:li.def-item
       [:a.link.dim.blue.dib.pa1.pl0
        {:href (str "#" def-name)}
        def-name]
       (when-not (= (set (map :platform platf-defs))
                    indicate-platforms-other-than)
         [:sup.f7.gray
          (-> (set (map :platform platf-defs))
              (humanize-supported-platforms))])])]])

(defn namespace-overview
  [ns-url ns defs]
  [:div
   [:a.link.black
    {:href ns-url}
    [:h2 ns
     [:img.ml2 {:src "https://icon.now.sh/chevron/12/357edd"}]]]
   (some-> meta :doc render-doc-string)
   [:ul.list.pl0
    (for [d defs]
      [:li.dib.mr3.mb2
       [:a.link.blue
        {:href (str ns-url "#" (:name d))}
        (:name d)]])]])

(defn sub-namespace-overview-page
  [{:keys [ns-entity namespaces defs top-bar-component article-list-component namespace-list-component]}]
  [:div.ns-page
   top-bar-component
   (layout/sidebar
    article-list-component
    namespace-list-component)
   (layout/main-container
    {:offset "16rem"}
    [:div.w-80-ns.pv5
     (for [meta (->> namespaces
                     (filter #(.startsWith (:name %) (:namespace ns-entity)))
                     (map #(dissoc % :platform)); see PLATF_SUPPORT
                     (set)
                     (sort-by key))
           :let [ns (:name meta)
                 ns-url (routes/url-for :artifact/namespace :path-params (assoc ns-entity :namespace ns))
                 defs (->> defs
                           (filter #(= ns (:namespace %)))
                           (sort-by :name))]]
       (namespace-overview ns-url meta defs))])])

(defn namespace-page [{:keys [ns-entity ns-data defs scm-info top-bar-component article-list-component namespace-list-component]}]
  (cljdoc.spec/assert :cljdoc.spec/namespace-entity ns-entity)
  (let [sorted-defs                        (sort-by (comp string/lower-case :name) defs)
        [[dominant-platf] :as platf-stats] (platform-stats defs)
        blob                               (or (:name (:tag scm-info)) (:commit scm-info))
        scm-base                           (str (:url scm-info) "/blob/" blob "/")
        file-mapping                       (when (:files scm-info)
                                             (fixref/match-files
                                              (keys (:files scm-info))
                                              (set (keep :file sorted-defs))))]
    [:div.ns-page
     top-bar-component
     (layout/sidebar
      article-list-component
      namespace-list-component)
     (layout/sidebar-two
      (platform-support-note platf-stats)
      (definitions-list ns-entity sorted-defs
        {:indicate-platforms-other-than dominant-platf}))
     (layout/main-container
      {:offset "32rem"}
      [:div.w-80-ns.pv4
       [:h2 (:namespace ns-entity)]
       (some-> ns-data :doc render-doc-string)
       (for [[def-name platf-defs] (->> defs
                                        (group-by :name)
                                        (sort-by key))
             :let [def-meta (first platf-defs)]] ;PLATF_SUPPORT
         (def-block platf-defs (when file-mapping
                                 (str scm-base (get file-mapping (:file def-meta)) "#L" (:line def-meta)))))])]))

(ns cljdoc.render.api
  "Functions related to rendering API documenation"
  (:require [cljdoc.render.rich-text :as rich-text]
            [cljdoc.render.layout :as layout]
            [cljdoc.util.ns-tree :as ns-tree]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.spec]
            [cljdoc.routes :as routes]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]))

(defn def-code-block
  [content]
  [:pre
   [:code.db.mb2 {:class "language-clojure"}
    content]])

(defn def-block [platforms {:keys [base file-mapping] :as scm}]
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
        (def-code-block (str "(" (:name def-meta) (when (seq argv) " ") (string/join " " argv) ")")))]
     (when (:doc def-meta)
       [:div.lh-copy.markdown
        (-> (:doc def-meta) rich-text/markdown-to-html hiccup/raw)])
     (when (seq (:members def-meta))
       [:div.lh-copy.pl3.bl.b--black-10
        (for [m (:members def-meta)]
          [:div
           [:h5 (:name m)]
           (for [argv (sort-by count (:arglists m))]
             (def-code-block (str "(" (:name m) " " (string/join " " argv) ")")))
           (when (:doc m)
             [:p (:doc m)])])])
     (when file-mapping
       [:a.link.f7.gray.hover-dark-gray
        {:href (str base (get file-mapping (:file def-meta)) "#L" (:line def-meta))}
        "source"])]))

(defn namespace-list [{:keys [current]} namespaces]
  (let [namespaces (ns-tree/index-by :namespace namespaces)]
    [:div
     (layout/sidebar-title "Namespaces")
     [:ul.list.pl2
      (for [[ns level _ leaf?] (ns-tree/namespace-hierarchy (keys namespaces))
            :let [style {:margin-left (str (* (dec level) 10) "px")}]]
        (if-let [ns (get namespaces ns)]
          [:li
           [:a.link.hover-dark-blue.blue.dib.pa1
            {:href (routes/path-for :artifact/namespace ns)
             :class (when (= (:namespace ns) current) "b")
             :style style}
            (->> (ns-tree/split-ns (:namespace ns))
                 (drop (dec level)))]]
          [:li.blue.pa1
           [:span
            {:style style}
            (->> (ns-tree/split-ns ns)
                 (drop (dec level)))]]))

      #_(for [ns (sort-by :namespace namespaces)]
          [:li
           [:a.link.dim.blue.dib.pa1
            {:href (routes/path-for :artifact/namespace ns)
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
        {:href (routes/path-for :artifact/def (merge ns-entity {:def def-name}))}
        def-name]
       (when-not (= (set (map :platform platf-defs))
                    indicate-platforms-other-than)
         [:sup.f7.gray
          (-> (set (map :platform platf-defs))
              (humanize-supported-platforms))])])]])

(defn namespace-page [{:keys [ns-entity ns-data defs scm-info top-bar-component article-list-component namespace-list-component]}]
  (cljdoc.spec/assert :cljdoc.spec/namespace-entity ns-entity)
  (let [sorted-defs                        (sort-by (comp string/lower-case :name) defs)
        [[dominant-platf] :as platf-stats] (platform-stats defs)
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
       (when-let [ns-doc (:doc ns-data)]
         [:div.lh-copy.markdown
          (-> ns-doc rich-text/markdown-to-html hiccup/raw)])
       (for [[def-name platf-defs] (->> defs
                                        (group-by :name)
                                        (sort-by key))
             :let [blob (or (:name (:tag scm-info)) (:commit scm-info))
                   scm-base (str (:url scm-info) "/blob/" blob "/")]]
         (def-block platf-defs (when file-mapping
                                 {:base scm-base
                                  :file-mapping file-mapping})))])]))

(ns cljdoc.client.lib-switcher
  (:require ["fuzzysort$default" :as fuzzysort]
            ["preact" :refer [h render]]
            ["preact/hooks" :refer [useEffect useRef useState]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]
            #_:clj-kondo/ignore ;; used in #jsx as tag
            [cljdoc.client.list-search :refer [ListSearch]]
            [clojure.string :as str]))

(warn-on-lazy-reusage!)

(defn- is-same-project [p1 p2]
  (and (== (:group-id p1) (:group-id p2))
       (== (:artifact-id p1) (:artifact-id p2))))

(defn- load-prev-opened []
  (.parse js/JSON (or (.getItem js/localStorage "previouslyOpened") "[]")))

(defn- is-error-page? []
  (.querySelector js/document "head > title[data-error]"))

(defn- track-project-opened []
  (when (not (is-error-page?))
    (let [max-track-count 15
          project (lib/coords-from-current-loc)]
      (when project
        (let [prev-opened (->> (load-prev-opened)
                               (remove #(is-same-project project %))
                               doall)
              dated-project (assoc project :last_viewed (.toUTCString (js/Date.)))
              _ (.push prev-opened dated-project)
              prev-opened (->> prev-opened
                               (take max-track-count)
                               doall)]
          (.setItem js/localStorage "previouslyOpened" (.stringify js/JSON prev-opened)))))))

(defn- RowView [{:keys [result isSelected selectResult]}]
  (let [uri (lib/docs-path result)
        row-class (if isSelected
                    "pa3 bb b--light-gray bg-light-blue"
                    "pa3 bb b--light-gray")]
    #jsx [:<>
          [:a {:class "no-underline black" :href uri :onMouseOver selectResult}
           [:div {:class row-class}
            [:h4 {:class "dib ma0"}
             (lib/project result)
             [:span {:class "ml2 gray normal"} (:version result)]]
            [:a {:class "link blue ml2" :href uri}
             "view docs"]]]]))

(defn- load-recently-visited-docs []
  (some->> (load-prev-opened)
           (mapv (fn [{:keys [artifact-id group-id] :as p}]
                   (assoc p :project-id (if (= group-id artifact-id)
                                          group-id
                                          (str group-id "/" artifact-id)))))
           reverse
           doall))

(defn- Switcher []
  (let [recently-visited-docs (load-recently-visited-docs)
        [results               set-results!] (useState recently-visited-docs)
        [show                  set-show!] (useState false)

        background-node (useRef nil)

        is-macos? (-> js/navigator.platform str/upper-case (.indexOf "MAC") (>= 0))

        reset-state (fn []
                      (set-results! recently-visited-docs))
        on-global-key-down (fn [{:keys [key metaKey ctrlKey] :as e}]
                             (cond
                               (and (= "k" key)
                                    (or (and is-macos? metaKey) (and (not is-macos?) ctrlKey)))
                               (do
                                 (.preventDefault e)
                                 (set-show! true)
                                 (set-results! recently-visited-docs))

                               (= "Escape" key)
                               (do
                                 (set-show! false)
                                 (set-results! []))))
        update-results (fn [search-str]
                         (if (= "" search-str)
                           (reset-state)
                           (let [fuzzy-sort-options {:key :project-id}
                                 results (.go fuzzysort search-str recently-visited-docs fuzzy-sort-options)]
                             (set-results! (mapv #(.-obj %) results)))))]
    (useEffect (fn []
                 (.addEventListener js/document "keydown" on-global-key-down))
               [])

    (when show
      #jsx [:<>
            [:div {:class "bg-black-30 fixed top-0 right-0 bottom-0 left-0 sans-serif"
                   :ref background-node
                   :onClick (fn [e]
                              (when (= e.target (.-current background-node))
                                (set-show! false)))}
             [:div {:class "mw7 center mt6 bg-white pa3 br2 shadow-3"}
              [:ListSearch {:show show
                            :initialValue ""
                            :place-holder-text "Jump to recently viewed docs..."
                            :results results
                            :rowView RowView
                            :resultsFetcher update-results
                            :onActivateItem (fn [item] (.open js/window (lib/docs-path item) "_self"))}]]]])))

(defn init []
  (track-project-opened)
  (when-let [switcher-node (dom/query "[data-id='cljdoc-switcher']")]
    (render (h Switcher)
            switcher-node)))

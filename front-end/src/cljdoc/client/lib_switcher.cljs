(ns cljdoc.client.lib-switcher
  (:require ["fuzzysort$default" :as fuzzysort]
            ["preact" :refer [h render]]
            ["preact/hooks" :refer [useEffect useRef useState]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]
            #_:clj-kondo/ignore ;; used in #jsx as tag
            [cljdoc.client.listselect :refer [ResultsView]]))

(defn- is-same-project [p1 p2]
  (and (== (:group-id p1) (:group-id p2))
       (== (:artifact-id p1) (:artifact-id p2))))

(defn- load-prev-opened []
  (.parse js/JSON (or (.getItem js/localStorage "previouslyOpened") "[]")))

(defn- is-error-page? []
  (.querySelector js/document "head > title[data-error]"))

(defn track-project-opened []
  (when (not (is-error-page?))
    (let [max-track-count 15
          project (lib/coords-from-current-loc)]
      (when project
        (let [prev-opened (->> (load-prev-opened)
                               (remove #(is-same-project project %))
                               doall)
              _ (.push prev-opened project)
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

(defn- Switcher []
  (let [prev-opened (some->> (load-prev-opened)
                             (mapv (fn [{:keys [artifact-id group-id] :as p}]
                                     (assoc p :project-id (if (= group-id artifact-id)
                                                            group-id
                                                            (str group-id "/" artifact-id)))))
                             reverse
                             doall)

        [selected-ndx set-selected-ndx!] (useState 0)
        ;; TODO: why are we saving both results and prev-opened?
        [results      set-results!] (useState prev-opened)
        [prev-opened  set-prev-opened!] (useState prev-opened)
        [show         set-show!] (useState false)

        background-node (useRef nil)
        input-node (useRef nil)

        is-macos? (-> js/navigator.platform .toUpperCase (.indexOf "MAC") (>= 0))

        reset-state (fn []
                      (set-selected-ndx! 0)
                      (set-results! prev-opened)
                      (set-prev-opened! prev-opened))
        on-input-key-down (fn [{:keys [key] :as e}]
                            (case key
                              "Enter"
                              (let [{:keys [group-id artifact-id version]} (get results selected-ndx)]
                                (set! js/window.location.href (str "/d/" group-id "/" artifact-id "/" version)))
                              "ArrowUp"
                              (do
                                (.preventDefault e)
                                (set-selected-ndx! (max (dec selected-ndx) 0)))
                              "ArrowDown"
                              (do
                                (.preventDefault e)
                                (set-selected-ndx! (min (inc selected-ndx)
                                                        (-> results count dec))))
                              nil))
        on-global-key-down (fn [{:keys [key metaKey ctrlKey] :as e}]
                             (cond
                               (and (= "k" key)
                                    (or (and is-macos? metaKey) (and (not is-macos?) ctrlKey)))
                               (do
                                 (.preventDefault e)
                                 (set-show! true)
                                 (set-results! prev-opened))

                               (= "Escape" key)
                               (do
                                 (set-show! false)
                                 (set-results! []))))
        update-results (fn [search-str]
                         (if (= "" search-str)
                           (reset-state)
                           (let [fuzzy-sort-options {:key :project-id}
                                 results (.go fuzzysort search-str prev-opened fuzzy-sort-options)]
                             (set-results! (mapv #(.-obj %) results))
                             (set-selected-ndx! 0))))]
    (useEffect (fn []
                 (.addEventListener js/document "keydown" on-global-key-down))
               [])
    (useEffect (fn []
                 (when (and show (.-current input-node))
                   (-> input-node .-current .focus)))
               [show])
    (when show
      #jsx [:<>
            [:div {:class "bg-black-30 fixed top-0 right-0 bottom-0 left-0 sans-serif"
                   :ref background-node
                   :onClick (fn [e]
                              (when (= e.target (.-current background-node))
                                (set-show! false)))}
             [:div {:class "mw7 center mt6 bg-white pa3 br2 shadow-3"}
              [:input {:placeholder "Jump to recently viewed docs..."
                       :class "pa2 w-100 br1 border-box b--blue ba input-reset"
                       :ref input-node
                       :onKeyDown on-input-key-down
                       :onInput (fn [e]
                                  (update-results (-> e .-target .-value)))}]
              [:ResultsView {:rowView RowView
                             :results results
                             :selectedIndex selected-ndx
                             :onMouseOver set-selected-ndx!}]]]])))

(defn init []
  (track-project-opened)
  (when-let [switcher-node (dom/query-doc "[data-id='cljdoc-switcher']")]
    (render (h Switcher)
            switcher-node)))

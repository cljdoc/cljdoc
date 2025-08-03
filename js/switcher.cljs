(ns switcher
  (:require ["preact/hooks" :refer [useState useEffect useRef]]
            ["fuzzysort$default" :as fuzzysort]
            ["./library" :as library]
            ["./listselect" :refer [ResultsView]]))

(defn- is-same-project [p1 p2]
  (and (== (:group-id p1) (:group-id p2))
       (== (:artifact-id p1) (:artifact-id p2))))

(defn- load-prev-opened []
  (JSON/parse (or (.getItem localStorage "previouslyOpened") "[]")))

(defn- is-error-page? []
  (.querySelector document "head > title[data-error]"))

(defn trackProjectOpened []
  (when (not (is-error-page?))
    (let [max-track-count 15
          project (library/coords-from-current-loc)]
      (when project
        ;; TODO: also referred to in recent_doc_links.cljs
        (.log console "project" project)
        (.log console "foo" (load-prev-opened))
        (.log console "dated proj" (assoc project :last-viewed (.toUTCString (Date.))))
        (let [prev-opened (->> (load-prev-opened)
                               (remove #(is-same-project project %))
                               doall)
              _ (.push prev-opened project)
              prev-opened (->> prev-opened
                               (take max-track-count)
                               doall)]
          (.log console "saving prev opened" prev-opened)
          (.setItem localStorage "previouslyOpened" (JSON/stringify prev-opened)))))))



(defn- SwitcherSingleResultView [{:keys [result isSelected selectResult]}]
  (.log console "ssrv!!!" result isSelected selectResult)
  (let [_ (.log console "foo+++" (library/project result))
        uri (library/docs-path result)]
    #jsx [:<>
          [:a {:class "no-underline black" :href uri :onMouseOver selectResult}
           [:div {:class (if isSelected
                           "pa3 bb b--light-gray bg-light-blue"
                           "pa3 bb b--light-gray")}
            [:h4 {:class "dib ma0"}
             (library/project result)
             [:span {:class "ml2 gray normal"} (:version result)]]
            [:a {:class "link blue ml2" :href uri}
             "view docs"]]]]))

(defn Switcher []
  (let [prev-opened (some->> (load-prev-opened)
                             (mapv (fn [{:keys [artifact-id group-id] :as p}]
                                     (assoc p :project-id (if (= group-id artifact-id)
                                                            group-id
                                                            (str group-id "/" artifact-id)))))
                             reverse
                             doall)

        [selected-ndx set-selected-ndx!] (useState 0)
        [results      set-results!] (useState prev-opened)
        [prev-opened  set-prev-opened!] (useState prev-opened)
        [show         set-show!] (useState false)

        background-node (useRef nil)
        input-node (useRef nil)

        is-macos? (-> navigator.platform .toUpperCase (.indexOf "MAC") (>= 0))

        reset-state (fn []
                      (.log console "resetting state")
                      (set-selected-ndx! 0)
                      (set-results! prev-opened)
                      (set-prev-opened! prev-opened))
        handle-key-down (fn [{:keys [target key metaKey ctrlKey] :as e}]
                          (.log console "key" key)
                          (when (= target (.-current input-node))
                            (cond
                              (= "ArrowUp" key)
                              (do
                                (.preventDefault e)
                                (set-selected-ndx! (max (dec selected-ndx) 0)))
                              (= "Arrowdown" key)
                              (do
                                (.preventDefault e)
                                (set-selected-ndx! (min
                                                     (inc selectected-ndx)
                                                     (-> results count dec))))))

                          (cond
                            (and (= "k" key)
                                 (or (and is-macos? metaKey) (and (not is-macos?) ctrlKey))
                                 (= target document.body))
                            (do
                              (.log console "handling K key")
                              (.preventDefault e)
                              (set-show! true)
                              (set-results! prev-opened))

                            (= "Escape" key)
                            (do
                              (.log console "handling escape")
                              (set-show! false)
                              (set-results! []))))
        handle-input-key-up (fn [{:keys [key] :as e}]
                              (when (= "Enter" key)
                                (let [{:keys [group-id artifact-id version]} (get results selected-ndx)]
                                  (set! window.location.href (str "/d/" group-id "/" artifact-id "/" version)))))
        update-results (fn [search-str]
                         (.log console "update results" search-str)
                         (.log console "fuzzysort" fuzzysort)
                         (if (= "" search-str)
                           (reset-state)
                           (let [fuzzy-sort-options {:key :project-id}
                                 _ (.log console "fuz o" prev-opened)
                                 results (.go fuzzysort search-str prev-opened fuzzy-sort-options)]
                             (.log console "fuzzy results" (mapv #(.-obj %) results))
                             (set-results! (mapv #(.-obj %) results))
                             (set-selected-ndx! 0))))]
    (useEffect (fn []
                 (.log console "ue init")
                 (.addEventListener document "keydown" handle-key-down))
               [])
    (useEffect (fn []
                 (.log console "ue show" show)
                 (when (and show (.-current input-node))
                   (-> input-node .-current .focus)))
               [show])
    (when show
      (.log console "rendering show" show results)
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
                       :onKeyUp handle-input-key-up
                       :onInput (fn [e]
                                  (.log console "onInput" e)
                                  (update-results (-> e .-target .-value)))}]
              [:ResultsView {:results results
                             :selectedIndex selected-ndx
                             :onMouseOver set-selected-ndx!
                             :resultView SwitcherSingleResultView}]]]])))

(ns cljdoc.client.switcher
  (:require ["./library" :as library]
            #_:clj-kondo/ignore ;; used in #jsx as tag
            ["./listselect" :refer [ResultsView]]
            ["fuzzysort$default" :as fuzzysort]
            ["preact/hooks" :refer [useEffect useRef useState]]))

(defn- is-same-project [p1 p2]
  (and (== (:group-id p1) (:group-id p2))
       (== (:artifact-id p1) (:artifact-id p2))))

(defn- load-prev-opened []
  (.parse js/JSON (or (.getItem js/localStorage "previouslyOpened") "[]")))

(defn- is-error-page? []
  (.querySelector js/document "head > title[data-error]"))

(defn trackProjectOpened []
  (when (not (is-error-page?))
    (let [max-track-count 15
          project (library/coords-from-current-loc)]
      (when project
        ;; TODO: also referred to in recent_doc_links.cljs
        (.log js/console "project" project)
        (.log js/console "foo" (load-prev-opened))
        (.log js/console "dated proj" (assoc project :last-viewed (.toUTCString (js/Date.))))
        (let [prev-opened (->> (load-prev-opened)
                               (remove #(is-same-project project %))
                               doall)
              _ (.push prev-opened project)
              prev-opened (->> prev-opened
                               (take max-track-count)
                               doall)]
          (.log js/console "saving prev opened" prev-opened)
          (.setItem js/localStorage "previouslyOpened" (.stringify js/JSON prev-opened)))))))

(defn- SwitcherSingleResultView [{:keys [result isSelected selectResult]}]
  (.log js/console "ssrv" result isSelected selectResult)
  (let [uri (library/docs-path result)
        row-class (if isSelected
                    "pa3 bb b--light-gray bg-light-blue"
                    "pa3 bb b--light-gray")]
    #jsx [:<>
          [:a {:class "no-underline black" :href uri :onMouseOver selectResult}
           [:div {:class row-class}
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

        is-macos? (-> js/navigator.platform .toUpperCase (.indexOf "MAC") (>= 0))

        reset-state (fn []
                      (.log js/console "resetting state")
                      (set-selected-ndx! 0)
                      (set-results! prev-opened)
                      (set-prev-opened! prev-opened))
        on-input-key-down (fn [{:keys [key] :as e}]
                            (.log js/console "switcher key" key)
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
                                (.log js/console "ad" selected-ndx (count results)
                                      (min (inc selected-ndx) (-> results count dec)))
                                (.preventDefault e)
                                (set-selected-ndx! (min (inc selected-ndx)
                                                        (-> results count dec))))
                              nil))
        on-global-key-down (fn [{:keys [key metaKey ctrlKey] :as e}]
                             (cond
                               (and (= "k" key)
                                    (or (and is-macos? metaKey) (and (not is-macos?) ctrlKey)))
                               (do
                                 (.log js/console "handling K key")
                                 (.preventDefault e)
                                 (set-show! true)
                                 (set-results! prev-opened))

                               (= "Escape" key)
                               (do
                                 (.log js/console "handling escape")
                                 (set-show! false)
                                 (set-results! []))))
        update-results (fn [search-str]
                         (.log js/console "update results" search-str)
                         (.log js/console "fuzzysort" fuzzysort)
                         (if (= "" search-str)
                           (reset-state)
                           (let [fuzzy-sort-options {:key :project-id}
                                 _ (.log js/console "fuz o" prev-opened)
                                 results (.go fuzzysort search-str prev-opened fuzzy-sort-options)]
                             (.log js/console "fuzzy results" (mapv #(.-obj %) results))
                             (set-results! (mapv #(.-obj %) results))
                             (set-selected-ndx! 0))))]
    (useEffect (fn []
                 (.log js/console "ue init")
                 (.addEventListener js/document "keydown" on-global-key-down))
               [])
    (useEffect (fn []
                 (.log js/console "ue show" show)
                 (when (and show (.-current input-node))
                   (-> input-node .-current .focus)))
               [show])
    (when show
      (.log js/console "rendering show" show results)
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
                                  (.log js/console "onInput" e)
                                  (update-results (-> e .-target .-value)))}]
              [:ResultsView {:resultView SwitcherSingleResultView
                             :results results
                             :selectedIndex selected-ndx
                             :onMouseOver set-selected-ndx!}]]]])))

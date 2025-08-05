(ns cljdoc.client.list-search
  "Support for an input that searches items and presents them in a selectable list."
  (:require ["preact" :refer [h]]
            ["preact/hooks" :refer [useEffect useState useRef]]
            #_:clj-kondo/ignore ;; used in #jsx as tag
            [clojure.string :as str]))

(defn- #_:clj-kondo/ignore ;; used in jsx as tag
  SearchInput [{:keys [place-holder-text initial-value focus unfocus results-fetcher
                       onEnter onArrowUp onArrowDown]}]
  (let [on-key-down (fn [{:keys [key] :as e}]
                      (case key
                        "Enter" (onEnter)
                        "Escape" (unfocus)
                        "ArrowUp" (do (.preventDefault e)
                                      (onArrowUp))
                        "ArrowDown" (do (.preventDefault e)
                                        (onArrowDown))
                        nil))
        input-node (useRef nil)
        [input-value set-input-value!] (useState (or initial-value ""))]
    (useEffect (fn setup []
                 (.log js/console "searchinput effect")
                 (-> input-node .-current .focus)
                 (when initial-value
                   (results-fetcher initial-value)))
               [])
    #jsx [:input {:ref input-node
                  :autofocus true
                  :placeHolder place-holder-text
                  :class "pa2 w-100 br1 border-box b--blue ba input-reset"
                  :value input-value
                  :onFocus focus
                  :onBlur (fn [_e] (setTimeout unfocus 200))
                  :onKeyDown on-key-down
                  :onInput (fn [e]
                             (let [target (.-target e)]
                               (set-input-value! (.-value target))
                               (results-fetcher (.-value target))))}]))

(defn- keep-selection-in-viewport
  "Ensure that item at `selected-index` is visible by scrolling to it within `container`"
  [container selected-index]
  (let [container-rect (.getBoundingClientRect container)
        selected-rect (.getBoundingClientRect (aget (.-children container) selected-index))
        delta-top (- (.-top selected-rect) (.-top container-rect))
        delta-bottom (- (.-bottom selected-rect) (.-bottom container-rect))]
    (cond
      (< delta-top 0) (.scrollBy container 0 delta-top)
      (> delta-bottom 0) (.scrollBy container 0 delta-bottom))))

(defn- #_:clj-kondo/ignore ;; used in jsx as tag
  ResultsView [{:keys [rowView results selectedIndex selectResult]}]
  (let [results-view-node (useRef nil)]
    (useEffect (fn []
                 (when results-view-node
                   (keep-selection-in-viewport (.-current results-view-node)
                                               selectedIndex)))
               [selectedIndex])
    #jsx [:<>
          [:div
           {:className "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
            :style {:maxHeight "20rem"}
            :ref results-view-node}
           (-> (for [[idx result] (map-indexed vector results)]
                 #jsx [:div {:onMouseOver (fn [] (selectResult idx))}
                       (h rowView
                          {:result result
                           :isSelected (= selectedIndex idx)})])
               doall)]]))

(defn ListSearch [{:keys [initialValue place-holder-text results rowView resultsFetcher onActivateItem]}]
  (let [[selected-ndx set-selected-ndx!] (useState 0)
        [focused      set-focused!] (useState false)]
    #jsx [:<>
          [:div {:class "relative system-sans-serif"}
           [:SearchInput {:initial-value initialValue
                          :place-holder-text place-holder-text
                          :results-fetcher resultsFetcher
                          :onEnter (fn []
                                     (when-let [item (get results selected-ndx)]
                                       (.log js/console "onEnter" item onActivateItem)
                                       (set-focused! true)
                                       (onActivateItem item)
                                       (set-selected-ndx! 0)))
                          :onArrowUp (fn []
                                       (set-selected-ndx! (max (dec selected-ndx) 0)))
                          :onArrowDown (fn []
                                         (set-selected-ndx! (min (inc selected-ndx)
                                                                 (-> results count dec))))
                          :focus (fn [] (set-focused! true))
                          :unfocus (fn [] (set-focused! false))}]
           (when (and focused (seq results))
             #jsx [:<>
                   [:div {:class "bg-white br1 br--bottom bb bl br b--blue w-100 absolute"
                          :style "top: 2.3rem; box-shadow: 0 4px 10px rgba(0,0,0,0.1)"}
                    [:ResultsView {:rowView rowView
                                   :results results
                                   :selectedIndex selected-ndx
                                   :selectResult set-selected-ndx!}]]])]]))

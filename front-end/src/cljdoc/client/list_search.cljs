(ns cljdoc.client.list-search
  "Support for an input that searches items and presents them in a selectable list."
  (:require ["preact" :refer [h]]
            ["preact/hooks" :refer [useEffect useState useRef]]))

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

(defn ListSearch [{:keys [initialValue place-holder-text results rowView resultsFetcher onActivateItem]}]
  (let [[selected-ndx set-selected-ndx!] (useState 0)
        input-node (useRef nil)
        [input-value set-input-value!] (useState (or initialValue ""))
        [refresh set-refresh!] (useState 0)] ;; to force refresh when we want
    (useEffect (fn setup []
                 (.focus (.-current input-node))
                 (when initialValue
                   (resultsFetcher initialValue)))
               [])
    #jsx [:<>
          [:div {:class "relative system-sans-serif"}
           [:input {:ref input-node
                    :autofocus "true"
                    :placeHolder place-holder-text
                    :class "pa2 w-100 br1 border-box b--blue ba input-reset"
                    :value input-value
                    :onFocus (fn []
                               (set-selected-ndx! 0)
                               (set-refresh! (inc refresh)))
                    :onBlur (fn [] (set-refresh! (inc refresh)))
                    :onKeyDown (fn [{:keys [target key] :as e}]
                                 (case key
                                   "Enter" (when-let [item (get results selected-ndx)]
                                             (onActivateItem item))
                                   "Escape" (.blur target)
                                   "ArrowUp" (do (.preventDefault e)
                                                 (set-selected-ndx! (max (dec selected-ndx) 0)))
                                   "ArrowDown" (do (.preventDefault e)
                                                   (set-selected-ndx! (min (inc selected-ndx)
                                                                           (-> results count dec))))
                                   nil))
                    :onInput (fn [e]
                               (let [target (.-target e)]
                                 (set-input-value! (.-value target))
                                 (resultsFetcher (.-value target))))}]
           (when (and (.-hasFocus js/document)
                      (= (.-current input-node) (.-activeElement js/document))
                      (seq results))
             (let [results-view-node (useRef nil)]
               (useEffect (fn []
                            (when results-view-node
                              (keep-selection-in-viewport (.-current results-view-node)
                                                          selected-ndx)))
                          [selected-ndx])
               #jsx [:<>
                     [:div {:class "bg-white br1 br--bottom bb bl br b--blue w-100 absolute"
                            :style "top: 2.3rem; box-shadow: 0 4px 10px rgba(0,0,0,0.1)"}
                      [:div
                       {:className "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
                        :style {:maxHeight "20rem"}
                        :ref results-view-node}
                       (-> (for [[idx result] (map-indexed vector results)]
                             #jsx [:div {:onMouseOver (fn []
                                                        (set-selected-ndx! idx))
                                         :onMouseDown (fn []
                                                        (set-selected-ndx! idx)
                                                        (onActivateItem (get results idx)))}
                                   (h rowView
                                      {:result result
                                       :isSelected (= selected-ndx idx)})])
                           doall)]]]))]]))

(ns listselect
  (:require ["preact/hooks" :refer [useRef useEffect]]))

(defn- restrict-to-viewport [container selected-index]
  (let [container-rect (.getBoundingClientRect container)
        selected-rect (.getBoundingClientRect (aget (.-children container) selected-index))
        delta-top (- (.-top selected-rect) (.-top container-rect))
        delta-bottom (- (.-bottom selected-rect) (.-bottom container-rect))]
    (cond
      (< delta-top 0) (.scrollBy container 0 delta-top)
      (> delta-bottom 0) (.scrollBy container 0 delta-bottom))))

(defn ResultsView [{:keys [resultView results selectedIndex onMouseOver]}]
  (useEffect (fn []
               (.log console "huston we have been updated")
               (restrict-to-viewport ?container? selectedIndex))
             [selectedIndex])
  #jsx [:<>
        [:div
         {:className "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
          :style {:maxHeight "20rem"}
          :ref #(set! (.-resultsViewNode this) %)}
         (for [[idx result] (map-indexed vector results)]
           [:resultView
             {:result result
              :isSelected (= selectedIndex idx)
              :selectResult (fn [] (onMouseOver idx))}]

           )]])

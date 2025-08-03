(ns listselect
  (:require ["preact/hooks" :refer [useEffect useRef]]))

(defn- restrict-to-viewport [container selected-index]
  (.log console "rtvp" container selected-index)
  (let [container-rect (.getBoundingClientRect container)
        selected-rect (.getBoundingClientRect (aget (.-children container) selected-index))
        delta-top (- (.-top selected-rect) (.-top container-rect))
        delta-bottom (- (.-bottom selected-rect) (.-bottom container-rect))]
    (cond
      (< delta-top 0) (.scrollBy container 0 delta-top)
      (> delta-bottom 0) (.scrollBy container 0 delta-bottom))))

(defn ResultsView [{:keys [resultView results selectedIndex onMouseOver]}]
  (let [results-view-node (useRef nil)]
    (useEffect (fn []
                 (.log console "huston we have been updated" results-view-node)
                 #_(when results-view-node
                     (restrict-to-viewport (.-current results-view-node)
                                           selectedIndex)))
               [selectedIndex])
    (.log console "ResultsView" resultView results selectedIndex onMouseOver)
    #jsx [:<>
          [:div
           {:className "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
            :style {:maxHeight "20rem"}
            :ref results-view-node}
           (-> (for [[idx result] (map-indexed vector results)]
                 (do
                   (.log console "lp" idx result)
                   ;; TODO need to call with preact h?
                   (resultView
                    {:result result
                     :isSelected (= selectedIndex idx)
                     :selectResult (fn [] (onMouseOver idx))})))
               doall)]]))

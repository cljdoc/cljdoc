(ns listselect
  (:require ["preact" :refer [Component]]
            [squint.core :refer [defclass]]))

(defn- restrict-to-viewport [container selected-index]
  (let [container-rect (.getBoundingClientRect container)
        selected-rect (.getBoundingClientRect (aget (.-children container) selected-index))
        delta-top (- (.-top selected-rect) (.-top container-rect))
        delta-bottom (- (.-bottom selected-rect) (.-bottom container-rect))]
    (cond
      (< delta-top 0) (.scrollBy container 0 delta-top)
      (> delta-bottom 0) (.scrollBy container 0 delta-bottom))))

(defclass ResultsViewComponent
  (extends Component)

  Object
  (componentDidUpdate [this prev-props _prev-state]
                      (let [current-props (.-props this)
                            prev-selected (.-selectedIndex prev-props)
                            current-selected (.-selectedIndex current-props)]
                        (when (and (not= current-selected prev-selected)
                                   (.-resultsViewNode this))
                          (restrict-to-viewport (.-resultsViewNode this) current-selected))))

  Object
  (render [this props]
          (let [ResultView (.-resultView props)
                results (.-results props)
                selected-index (.-selectedIndex props)
                on-mouse-over (.-onMouseOver props)]
            #jsx [:<>
                  [:div
                   {:className "bg-white br1 br--bottom bb bl br b--blue w-100 overflow-y-scroll"
                    :style {:maxHeight "20rem"}
                    :ref #(set! (.-resultsViewNode this) %)}
                   (for [[idx result] (map-indexed vector results)]
                     [ResultView
                      {:key (str "result-" idx)
                       :result result
                       :isSelected (= idx selected-index)
                       :selectResult #(on-mouse-over idx)}])]])))

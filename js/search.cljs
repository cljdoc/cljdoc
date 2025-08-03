(ns search
  (:require ["preact/hooks" :refer [useState useEffect]]
            ["./library" :as library]
            ["./listselect" :refer [ResultsView]]))

(defn debounced [delay-ms f]
  (let [timer-id (atom nil)]
    (fn [& args]
      (when @timer-id
        (js/clearTimeout @timer-id))
      (js/Promise.
       (fn [resolve reject]
         (reset! timer-id
                 (js/setTimeout
                  (fn []
                    (try
                      (-> (apply f args) resolve)
                      (catch :default e
                        (reject e))
                      (finally
                        (reset! timer-id nil)))) delay-ms)))))))

(defn- clean-search-str [s]
  (.replace s "/[{}[]\"]+/g" ""))

(defn- load-results [q call-back]
  ;; TODO: url changed for testing, change back to relative
  ;; TODO server could just return edn?
  (.log console "lr" q call-back)
  (let [url (str "http://localhost:8000/api/search?q=" q)]
    (-> (js/fetch url)
        (.then (fn [response] (.json response)))
        (.then (fn [json] (.-results json)))
        (.then (fn [results] (call-back results))))))

(def ^:private load-results-debounced (debounced 300 load-results))

(defn- SearchInput [{:keys [initialValue focus unfocus newResultsCallback
                            onEnter onArrowUp onArrowDown] :as props}]
  (.log console "si props" props)
  (let [on-key-down (fn [{:keys [key] :as e}]
                      (case key
                        "Enter" (onEnter)
                        "Escape" (unfocus)
                        "ArrowUp" (do (.preventDefault e)
                                      (onArrowUp))
                        "ArrowDown" (do (.preventDefault e)
                                        (onArrowDown))
                        nil))

        [input-value set-input-value!] (useState (or initialValue ""))]
    (useEffect (fn []
                 (when initialValue
                   (.log console "si init" initialValue)
                   (load-results (clean-search-str initialValue) newResultsCallback)))
               [])
    #jsx [:input {:autofocus true
                  :placeHolder "Jump to docs..."
                  :class "pa2 w-100 br1 border-box b--blue ba input-reset"
                  :value input-value
                  :onFocus focus
                  :onBlur (fn [_e] (setTimeout (unfocus) 200))
                  :onKeyDown on-key-down
                  :onInput (fn [e]
                             (let [target (.-target e)]
                               (set-input-value! (.-value target))
                               (load-results-debounced (.-value target)
                                                       newResultsCallback)))}]))

(defn- SingleResultView [{:keys [result isSelected selectResult]}]
  ;; jar_name??
  (let [uri (library/docs-path result)
        rowClass (if isSelected
                   "pa3 bb b--light-gray bg-light-blue"
                   "pa3 bb b--light-gray")]
    #jsx [:<>
          [:a {:class "no-underline black" :href uri}
           [:div {:class rowClass :onMouseOver selectResult}
            [:h4 {:class "dib ma0"}
             (library/project result)
             [:span {:class "ml2 gray normal"}
              (:version result)]]
            [:a {:class "link blue ml2" :href uri}
             "view docs"]
            [:div {:class "gray f6"} (:blurb result)]
            [:div {:class "gray i f7"} (:origin result)]]]]))

(defn App [{:keys [initialValue]}]
  (let [[selected-ndx set-selected-ndx!] (useState 0)
        [results      set-results!] (useState [])
        [focused      set-focused!] (useState false)

        results-view (fn [selectResult]
                       #jsx [:<>
                             [:div {:class "bg-white br1 br--bottom bb bl br b--blue w-100 absolute"
                                    :style "top: 2.3rem; box-shadow: 0 4px 10px rgba(0,0,0,0.1)"}
                              [:ResultsView {:resultView SingleResultView
                                             :results results
                                             :selectedIndex selected-ndx
                                             :onMouseOver selectResult}]]])]
    #jsx [:<>
          [:div {:class "relative system-sans-serif"}
           (SearchInput {:initialValue initialValue
                         :newResultsCallback (fn [rs]
                                               (set-focused! true)
                                               (set-results! rs)
                                               (set-selected-ndx! 0))
                         :onEnter (fn []
                                    (when-let [result (get results selected-ndx)]
                                      (.open window (library/docs-path result) "_self")))
                         :onArrowUp (fn []
                                      (set-selected-ndx! (max (dec selected-ndx) 0)))
                         :onArrowDown (fn []
                                        (set-selected-ndx! (min (inc selected-ndx)
                                                                (-> results count dec))))
                         :onFocus (fn [] (set-focused! true))})
           (when (and focused (seq results))
             (results-view selected-ndx))]]))

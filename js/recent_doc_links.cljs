(ns recent-doc-links
  (:require ["./library" :as lib]
            ["preact" :as preact]))

(defn- day-date [date]
  (js/Date. (.getFullYear date)
            (.getMonth date)
            (.getDate date)))

(defn- last-viewed-message [{:keys [last_viewed]}]
  (when last_viewed
    (let [activity-day (day-date (js/Date. last_viewed))
          now-day (day-date (js/Date.))
          ms-per-day 86400000
          days-ago (int (/ (- now-day activity-day)
                           ms-per-day))]
      (str " last viewed "
           (cond
             (zero? days-ago) "today"
             (= 1 days-ago) "1 day ago"
             :else (str days-ago " days ago"))))))

(defn initRecentDocLinks [doc-links]
  (let [previously-opened (some-> js/localStorage
                                  (.getItem "previouslyOpened")
                                  (js/JSON.parse))]
    (when (> (count previously-opened) 0)
      (when (>= (count previously-opened) 3)
        (when-let [examples (.querySelector js/document "[data-id='cljdoc-examples']")]
          (.remove examples)))
      (preact/render
       #jsx [:div
             [:p {:class "mt4 mb0"}
              [:div {:class "fw5"} "Pick up where you left off:"]
              [:ul {:class "mv0 pl0 list"}
               (let [recents (-> previously-opened
                                 reverse
                                 (.slice 0 3))]
                 (.log console "recents" recents)
                 (mapv (fn [visited]
                         #jsx [:li {:class "mr1 pt2"}
                               [:a {:class "link blue nowrap"
                                    :href (lib/docs-path visited)}
                                (lib/project visited)
                                [:span {:class "gray f6"}
                                 (last-viewed-message visited)]]])
                       recents))]]]
       doc-links))))

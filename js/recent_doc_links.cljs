(ns recent-doc-links
  (:require ["./library" :as lib]
            ["preact" :as preact]))

(defn- day-date [date]
  (Date. (.getFullYear date)
         (.getMonth date)
         (.getDate date)))

(defn- last-viewed-message [{:keys [last_viewed]}]
  (when last_viewed
    (let [activity-day (day-date (Date. last_viewed))
          now-day (day-date (Date.))
          ms-per-day 86400000
          days-ago (int (/ (- now-day activity-day)
                           ms-per-day))]

      (.log console "days ago" activity-day now-day days-ago)
      ;; not actually accurate because date is UTC
      (str " last viewed "
           (cond
             (zero? days-ago) "today"
             (= 1 days-ago) "1 day ago"
             :else (str days-ago " days ago"))))))

(defn initRecentDocLinks [doc-links]
  (let [previously-opened (some-> localStorage
                                  (.getItem "previouslyOpened")
                                  (JSON/parse))]
    (when (> (count previously-opened) 0)
      (when (>= (count previously-opened) 3)
        (when-let [examples (.querySelector document "[data-id='cljdoc-examples']")]
          (.remove examples)))
      ;; TODO: consider not using preact? Don't bother if we need it for other things
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
                                     :href (lib/docsUri visited)}
                                 (lib/project visited)
                                 [:span {:class "gray f6"}
                                  (last-viewed-message visited)]]])
                        recents))]]]
        doc-links))))

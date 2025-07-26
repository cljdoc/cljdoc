(ns recent-doc-links
  (:require ["./library" :as lib]
            ["preact" :as preact]))

(defn- last-viewed-message [{:keys [last_viewed]}]
  (.log console "last_viewed" last_viewed)
  (when last_viewed
    (let [last-viewed-date (Date. (Date/parse last_viewed))
          days-in-ms 86400000
          days-ago (int (/ (- (.getTime (Date.))
                              (.getTime last-viewed-date))
                           days-in-ms))]
      (.log console "days ago" days-ago)
      (str " last viewed "
           (cond
             (zero? days-ago) "today" ;; not actually true because could technically be yesterday
             (= 1 days-ago) "day ago"
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

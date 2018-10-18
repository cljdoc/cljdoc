(ns cljdoc.render.error
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.util :as util]
            [cljdoc.render.home :as home]))

(def not-found-message-404
  "We couldn't find anything here...")

(defn not-found-404 []
  (->> [:div.pt4
        [:div.mt5.mt6-l.mw7.center.pa4.pa0-l
         [:p.f2-ns.f3.mv3.w-90-l.lh-copy not-found-message-404]
         [:p.lh-copy.i "What library are you looking for? Find it here 👇"]
         (home/search-app)]]
       (layout/page {})
       (str)))

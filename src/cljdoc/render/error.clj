(ns cljdoc.render.error
  (:require [cljdoc.util :as util]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.home :as home]))


(defn not-found-404 []
  (->> [:div.pt4
        [:div.mt5-ns.mt6-l.mw7.center.pa4.pa0-l
         [:p.f2-ns.f3.mv3.w-90-l.lh-copy "We couldn't find anything here..."]
         [:p.lh-copy.i "What library are you looking for? Find it here 👇"]
         (home/search-app)]]
       (layout/page {})
       (str)))

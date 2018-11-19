(ns cljdoc.render.error
  (:require [cljdoc.util :as util]
            [cljdoc.render.layout :as layout]
            [cljdoc.render.home :as home]))


(defn not-found-404 []
  (->> [:div.pt4
        [:div.mt5-ns.mt6-l.mw7.center.pa4.pa0-l
         [:h1.f2.f1-ns.b ":("]
         [:h2.f3.f2-ns.ttu "404 - Page Not Found"]
         [:p.lh-copy.i "What library are you looking for? Find it here 👇"]
         (home/search-app)]]
       (layout/page {})
       (str)))

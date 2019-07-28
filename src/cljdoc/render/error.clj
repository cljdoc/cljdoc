(ns cljdoc.render.error
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.render.search :as search]))


(defn not-found-404 []
  (->> [:div.pt4
        [:div.mt5-ns.mt6-l.mw7.center.pa4.pa0-l
         [:h1.f2.f1-ns.b ":("]
         [:h2.f3.f2-ns.ttu "404 - Page Not Found"]
         [:p.lh-copy.i "What library are you looking for? Find it here 👇"]
         (search/search-form)]]
       (layout/page {})
       (str)))

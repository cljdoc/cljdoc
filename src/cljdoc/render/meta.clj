(ns cljdoc.render.meta
  (:require [cljdoc.render.layout :as layout]))

(defn shortcuts []
  (->> [:div
        (layout/top-bar-generic)
        [:div.pa4-ns.pa2
         [:h1 "Shortcuts"]
         [:div
          [:p.fl.w-10.pa2.dib "⌘ K"]
          [:p.fl.w-third.pa2.dib "Jump to recently viewed docs"]]]]
       (layout/page {:title "shortcuts"})))
(ns cljdoc.render.meta
  (:require [cljdoc.render.layout :as layout]))

(defn- shortcut [key desc]
  [:tr
   [:td.pv2.pr4.pr7-ns.bb.b--black-20.nowrap key]
   [:td.pv2.pr1.bb.b--black-20 desc]])

(defn shortcuts []
  (->> [:div
        (layout/top-bar-generic)
        [:div.pa4-ns.pa2
         [:h1 "Shortcuts"]
         [:div.pa2.overflow-auto
          [:table.f6.w-100.mw6 {:cellspacing 0}
           [:tbody.lh-copy
            (shortcut "⌘ K" "Jump to recently viewed docs")
            (shortcut "←" "Move to previous page")
            (shortcut "→" "Move to next page")]]]]]
       (layout/page {:title "shortcuts"})))

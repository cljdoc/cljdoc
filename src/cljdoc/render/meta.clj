(ns cljdoc.render.meta
  (:require [cljdoc.render.layout :as layout]))

(defn- shortcut [key desc]
  [:tr.striped--near-white
   [:td.pv2.pr2.pl3.bb.b--black-20.nowrap.tr key]
   [:td.pv2.pr3.pl2.bb.b--black-20 desc]])

(defn shortcuts [context]
  (->> [:div
        (layout/top-bar-generic)
        [:div.pa4-ns.pa2.w-auto.flex.justify-center
         [:div
          [:h1 "Shortcuts"]
          [:div.overflow-auto
           [:table.f6.w-auto.mw6.ba.br2.b--black-10 {:cellspacing 0}
            [:tbody.lh-copy
             (shortcut "⌘ K" "Jump to recently viewed docs")
             (shortcut "←" "Move to previous page")
             (shortcut "→" "Move to next page")
             (shortcut "⌘ /" "Jump to the search field")]]]]]]
       (layout/page {:title "shortcuts"
                     :static-resources (:static-resources context)})))

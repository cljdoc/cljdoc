(ns cljdoc.render.home
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.util :as util]))

(defn home []
  (->> [:div.mw7.center.pv4.pa0-l.pa2
        [:h1.f1 "cljdoc"
         [:span.dib.v-mid.ml3.pv1.ph2.ba.b--moon-gray.br1.ttu.fw5.f7.gray.tracked "alpha"]]
        [:p.f2.lh-copy "is a platform to build, host and view
        documentation for Clojure/Script libraries."]
        [:p.lh-copy "Read " [:a.link.blue {:href (util/github-url :rationale)} "the rationale"]
         " or check out some existing documentation:"]
        (let [btn :a.dib.mr2.mb2.link.blue.pa2.ba.b--blue.br1]
          [:div.pr4
           [btn {:href "/d/bidi/bidi/2.1.3/"}
            [:code "[bidi \"2.1.3\"]"]]
           ;; Not working, will need investigation
           ;; [btn {:href "/d/funcool/cuerdas/2.0.5/"}
           ;;  [:code "[funcool/cuerdas \"2.0.5\"]"]]
           [btn {:href "/d/reagent/reagent/0.8.1/"}
            [:code "[reagent \"0.8.1\"]"]]
           [btn {:href "/d/compojure/compojure/1.6.1/"}
            [:code "[compojure \"1.6.1\"]"]]
           [btn {:href "/d/ring/ring-core/1.6.3/"}
            [:code "[ring/ring-core \"1.6.3\"]"]]
           [btn {:href "/d/clj-time/clj-time/0.14.3/"}
            [:code "[clj-time \"0.14.3\"]"]]
           [btn {:href "/d/rum/rum/0.11.2/"}
            [:code "[rum \"0.11.2\"]"]]
           [btn {:href "/d/re-frame/re-frame/0.10.5/"}
            [:code "[re-frame \"0.10.5\"]"]]
           ;; Disabling for now as namespace tree rendering
           ;; is still pretty bad
           ;; [btn {:href "/d/fulcrologic/fulcro/2.5.4/"}
           ;;  [:code "[fulcrologic/fulcro \"2.5.4\"]"]]
           ])

        [:div.mt4
         [:p "If you would like to publish documentation yourself, go to the following url:"]
         [:pre.lh-copy
          [:code
           "(str \"https://cljdoc.xyz/d/\""
           "\n     (:group-id your-project) \"/\""
           "\n     (:artifact-id your-project) \"/\""
           "\n     (:version your-project) \"/\")"]]
         [:p.lh-copy.f6.mid-gray [:span.fw5 "Tip: "] "If your project name does not contain a slash, group and artifact ID are the same."]
         [:p.lh-copy "After you've done that you may want to " [:a.link.blue {:href (util/github-url :userguide/articles)} "add additional articles to the sidebar."]]]

        [:div.mid-gray.mt4
         [:span.db.nb3 "—"]
         [:p.mid-gray "cljdoc is created by its " [:a.link.blue {:href (util/github-url :contributors) } "contributors"]
          ". Say hi in " [:a.link.blue {:href "https://clojurians.slack.com/messages/C8V0BQ0M6/"} "#cljdoc"] " on "
          [:a.link.blue {:href "http://clojurians.net/"} "Slack"] ". Report issues on " [:a.link.blue {:href (util/github-url :home)} "GitHub"] "."]]]
       (layout/page {:title "cljdoc"})
       (str)))


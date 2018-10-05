(ns cljdoc.render.build-req
  "HTML pages/fragments related to requesting a documentation build"
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.util.repositories :as repositories]
            [cljdoc.util :as util]))

(defn request-build-page [route-params]
  (->> [:div
        (layout/top-bar-generic)
        [:div.pa4-ns.pa2
         [:h1 "Want to build some documentation?"]
         [:p "We currently don't have documentation built for " (util/clojars-id route-params) " v" (:version route-params)]
         (if (repositories/find-artifact-repository (util/clojars-id route-params) (:version route-params))
           [:form.pv3 {:action "/api/request-build2" :method "POST"}
            [:input.pa2.mr2.br2.ba.outline-0.blue {:type "text" :id "project" :name "project" :value (str (:group-id route-params) "/" (:artifact-id route-params))}]
            [:input.pa2.mr2.br2.ba.outline-0.blue {:type "text" :id "version" :name "version" :value (:version route-params)}]
            [:input.ph3.pv2.mr2.br2.ba.b--blue.bg-white.blue.ttu.pointer.b {:type "submit" :value "build"}]
            [:p.mt4.mid-gray.f6.lh-copy.mw6 "After submitting this form you will be redirected to a page where you can track the progress of your documentation build."]]
           [:div
            [:p "We also can't find it on Clojars or Maven Central, which at this time, is required to build documentation."]
            [:p [:a.no-underline.blue {:href (util/github-url :issues)} "Let us know if this is unexpected."]]])]]
       (layout/page {:title (str "Build docs for " (util/clojars-id route-params))})))

(ns cljdoc.render.search
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.util :as util]))

(defn search-form
  ([] (search-form ""))
  ([search-terms]
   [:div.w-90.mb4
    [:div#cljdoc-search {:initial-value search-terms}]]))


(defn search-page [context]
  (->> [:div
        (layout/top-bar-generic)
        [:div.pt4
         [:div.mt5-ns.mw7.center.pa4.pa0-l
          [:h1.ma0
           [:span.dn "cljdoc beta"]
           [:img {:src "/cljdoc-logo-beta.svg" :alt "cljdoc logo" :width "150px"}]]
          [:p.f2-ns.f3.mv3.w-90-l.lh-copy "Library search:"]
          (search-form (-> context :request :query-params :q))]]]
       (layout/page {:title "cljdoc — documentation for Clojure/Script libraries"
                     :responsive? true})
       (str)))

;; TODO: complete this function later.
;; See https://developer.mozilla.org/en-US/docs/Web/OpenSearch for more information.
(defn suggest-api
  [context]
  (assoc context :response {:status 200
                            :body "{\"TODO\": \"Provide some JSON\"}"
                            :headers {"Content-Type" "application/x-suggestions+json"}}))

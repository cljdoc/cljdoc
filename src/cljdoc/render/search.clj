(ns cljdoc.render.search
  (:require [clojure.string :as string]
            [cljdoc.render.layout :as layout]
            [cheshire.core :as json]))

(defn search-form
  ([] (search-form ""))
  ([search-terms]
   [:div.w-90.mb4
    [:div#cljdoc-search {:data-initial-value search-terms}]]))


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

;; Temporary placeholder, to be removed once the suggestion API is using real data.
(def suggestion-candidates
  (into [] (sort ["ring"
                  "http-kit"
                  "pedestal"
                  "compojure"
                  "luminus"
                  "ataraxy"
                  "reitit"
                  "bidi"
                  "reagent"
                  "re-frame"
                  "om-next"
                  "fulcro"
                  "d2q"
                  "chestnut"
                  "integrant"
                  "component"
                  "clojure.java-time"
                  "clj-time"
                  "hiccup"
                  "instaparse"
                  "figwheel"
                  "datomic"])))

;; TODO: Re-implement to work with real data, and with the best possible performance in mind.
(defn- suggest [search-terms max-suggestion-count]
  (let [trimmed-terms (string/trim search-terms)]
    (into []
          (comp (filter #(string/starts-with? % trimmed-terms))
                (take max-suggestion-count))
          suggestion-candidates)))

(defn suggest-api
  "Provides suggestions for auto-completing the search terms the user is typing.
   Note: In Firefox, the response needs to reach the browser within 500ms otherwise it will be discarded.

   For more information, see:
   - https://developer.mozilla.org/en-US/docs/Web/OpenSearch
   - https://developer.mozilla.org/en-US/docs/Archive/Add-ons/Supporting_search_suggestions_in_search_plugins
   "
  [context]
  (let [search-terms (-> context :request :query-params :q)
        candidates (suggest search-terms 5)
        body (json/encode [search-terms candidates])]
    (assoc context
           :response {:status 200
                      :body body
                      :headers {"Content-Type" "application/x-suggestions+json"}})))

(ns cljdoc.server.search
  (:require [clojure.string :as string]
            [cheshire.core :as json]
            [clucie.core]
            [clucie.analysis]
            [clucie.store]
            [clucie.document]))

(def library-names
  (->> (slurp "https://clojars.org/stats/all.edn")
       (read-string)
       (keys)
       (mapv second)
       (sort)
       (dedupe)))

;; The first 2 libraries in the list will surprise you.
;(take 50 library-names)

;; It's over 9000 !!!
;(count library-names)

;; Some stats about library names w.r.t. their first letter.
;(->> (group-by first library-names)
;     (into {} (map (fn [[k v]]
;                     [k (count v)]))))

(def index-store (clucie.store/memory-store))
(def analyzer (clucie.analysis/standard-analyzer))

(with-open [writer (clucie.store/store-writer index-store analyzer)]
  (clucie.core/add! writer
                    (map (fn [name]
                           {:library-name name})
                         library-names)
                    [:library-name]))

(defn- suggest [search-terms max-suggestion-count]
  (let [terms (into #{}
                    (map #(str % "*"))
                    (re-seq #"\w+" search-terms))]
    (with-open [reader (clucie.store/store-reader index-store)]
      (->> (clucie.core/wildcard-search reader
                                        {:library-name terms}
                                        max-suggestion-count
                                        analyzer)
           (map :library-name)))))

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

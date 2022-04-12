(ns cljdoc.render.searchset-search
  (:require
   [cljdoc.server.routes :as routes]))

(defn searchbar
  [version-entity]
  [:div#js--single-docset-search {:data-searchset-url (routes/url-for :api/searchset
                                                                      :params
                                                                      version-entity)}])

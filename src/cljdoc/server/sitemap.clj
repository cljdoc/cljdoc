(ns cljdoc.server.sitemap
  (:require [sitemap.core :refer [generate-sitemap]]
            [clojure.tools.logging :as log]
            [cljdoc.storage.sqlite_impl :as storage]))


(defn sitemap [var]
  (generate-sitemap [{:loc "http://hashobject.com/about"
                      :lastmod "2013-05-31"
                      :changefreq "monthly"
                      :priority "0.8"}
                     {:loc "http://hashobject.com/team"
                      :lastmod "2013-06-01"
                      :changefreq "monthly"
                      :priority "0.9"}]))


(ns cljdoc.server.sitemap
  (:require [clojure.java.io :as io]
            [sitemap.core :as sitemap]
            [clojure.tools.logging :as log]
            [cljdoc.config :as cfg]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.routes :as routes]))

(defn- query->url-entries
  [{:keys [group_id artifact_id name]}]
  {:loc     (str "https://cljdoc.org"
                 (routes/url-for :artifact/version :path-params {:group-id    group_id
                                                                 :artifact-id artifact_id
                                                                 :version     name}))})

(defn- assert-valid-sitemap [sitemap]
  (if (seq (sitemap/validate-sitemap sitemap))
    (throw (ex-info "Invalide sitemap generated" {}))
    sitemap))

(defn build [db-spec]
  (->>
   (storage/all-distinct-docs db-spec)
   (map query->url-entries)
   (sitemap/generate-sitemap)
   (assert-valid-sitemap)))

(comment
  (def db-spec
    (-> (cfg/config)
        (cfg/db)
        (storage/->SQLiteStorage)))

  (routes/url-for :artifact/version :path-params {:group-id "a" :artifact-id "b" :version "c"})

  (def docs
    (storage/all-distinct-docs db-spec)
    )

  (query->url-entries (first docs))

  (sitemap/validate-sitemap
   (build db-spec)
   )

  (->>
   (sitemap/generate-sitemap [{:loc "http://example.com/about"
                       :lastmod "2014-07-00"
                       :changefreq "monthly"
                       :priority "0.5"}])
   (assert-valid-sitemap)
  )

  (->>
   (sitemap/generate-sitemap [{:loc "http://example.com/about"
                               :lastmod "2014-07-24"
                               :changefreq "monthly"
                               :priority "0.5"}])
   (assert-valid-sitemap)
   )

)

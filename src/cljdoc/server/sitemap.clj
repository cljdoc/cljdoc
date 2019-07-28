(ns cljdoc.server.sitemap
  (:require [clojure.spec.alpha :as spec]
            [sitemap.core :as sitemap]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.routes :as routes]))

(spec/fdef query->url-entries
  :args (spec/cat :version :cljdoc.spec/version-entity)
  :ret map?)

(defn- query->url-entries
  [version-entity]
  {:loc (str "https://cljdoc.org"
             (routes/url-for :artifact/version :path-params version-entity))})

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
  (require '[clojure.spec.test.alpha :as st])

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

(ns cljdoc.server.sitemap
  (:require [clojure.java.io :as io]
            [sitemap.core :refer [generate-sitemap validate-sitemap]]
            [clojure.tools.logging :as log]
            [cljdoc.config :as cfg]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.routes :as routes]))

(defn- query->url-entries
  [{:keys [group_id artifact_id name]}]
  {:loc     (str "https://cljdoc.org"
                 (routes/url-for :artifact/version :path-params {:group-id    group_id
                                                                 :artifact-id artifact_id
                                                                 :version     name}))
   :lastmod (.format (java.text.SimpleDateFormat. "yyyy-MM-dd") (java.util.Date.))})

(defn build [db-spec]
  (->>
   (storage/all-distinct-docs db-spec)
   (map query->url-entries)
   (generate-sitemap)))

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

  (validate-sitemap
   (build db-spec)
   )

  )


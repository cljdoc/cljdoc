(ns cljdoc.server.sitemap
  (:require [sitemap.core :refer [generate-sitemap]]
            [clojure.tools.logging :as log]
            [cljdoc.config :as cfg]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.routes :as routes]))


(defn sitemap [db-spec]
  (generate-sitemap
   (for [{:keys [group_id artifact_id] :as doc} (storage/all-distinct-docs db-spec)]
     {:loc
      (routes/url-for :artifact/current :path-params {:group-id group_id
                                                      :artifact-id artifact_id})}))
  )

(comment
  (def db-spec
    (-> (cfg/config)
        (cfg/db)
        (storage/->SQLiteStorage)))

  (routes/url-for :artifact/current :path-params {:group-id "a" :artifact-id "b"})

  (storage/all-distinct-docs db-spec)

  (sitemap db-spec)

  )


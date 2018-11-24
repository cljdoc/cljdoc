(require '[cljdoc.server.api :as api]
         '[cljdoc.util :as util]
         '[cljdoc.config :as cfg]
         '[clojure.java.jdbc :as sql]
         '[clojure.pprint :as pp]
         '[cljdoc.storage.sqlite-impl :as sqlite]
         '[cljdoc.util.repositories :as repositories])

(def db (cfg/db (cfg/config)))

(defn get-versions [db]
  (sql/query db
             ["SELECT group_id, artifact_id, name FROM versions WHERE release_date IS NULL ORDER BY id LIMIT 100"]
             {:row-fn #'sqlite/version-row-fn}))

(defn r-info [v]
  (let [project (str (:group-id v) "/" (:artifact-id v))
        v-ent   (util/version-entity project (:version v))
        a-uris  (repositories/artifact-uris project (:version v))]
    (println (:pom a-uris))
    (#'api/release-info (:pom a-uris))))

(loop [versions (get-versions db)]
  (println "Setting description for" (count versions) "releases")
  (let [v-r-info (zipmap versions (map r-info versions))]
    (time
     (sql/with-db-transaction [tx db]
       (doseq [[v-ent {:keys [release-date description]}] v-r-info]
         (sqlite/store-artifact! tx v-ent description release-date)))))
  (let [next-batch (get-versions db)]
    (when (seq next-batch)
      (recur next-batch))))


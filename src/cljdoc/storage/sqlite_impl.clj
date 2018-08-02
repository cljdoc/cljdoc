(ns cljdoc.storage.sqlite-impl
  (:require [cljdoc.spec]
            [cljdoc.util :as util]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy]
            [taoensso.tufte :as tufte :refer [defnp p profiled profile]]))


(defn store-artifact! [db-spec group-id artifact-id versions]
  (assert (coll? versions))
  (sql/execute! db-spec ["INSERT OR IGNORE INTO groups (id) VALUES (?)" group-id])
  (sql/execute! db-spec ["INSERT OR IGNORE INTO artifacts (group_id, id) VALUES (?, ?)" group-id artifact-id])
  (doseq [v versions]
    (sql/execute! db-spec ["INSERT OR IGNORE INTO versions (group_id, artifact_id, name) VALUES (?, ?, ?)" group-id artifact-id v])))

(defn- update-version-meta! [db-spec version-id data]
  (sql/execute! db-spec ["UPDATE versions SET meta = ? WHERE id = ?" (nippy/freeze data) version-id]))

(defn- write-ns!
  [db-spec version-id {:keys [platform name] :as data}]
  {:pre [(string? name) (string? platform)]}
  (sql/execute! db-spec ["INSERT INTO namespaces (version_id, platform, name, meta) VALUES (?, ?, ?, ?)"
                         version-id platform name (nippy/freeze data)]))

(defn- write-var!
  [db-spec version-id {:keys [platform namespace name] :as data}]
  {:pre [(string? name) (string? namespace) (string? platform)]}
  (sql/execute! db-spec ["INSERT INTO vars (version_id, platform, namespace, name, meta) VALUES (?, ?, ?, ?, ?)"
                         version-id platform namespace name (nippy/freeze data)]))

(defn clear-vars-and-nss! [db-spec version-id]
  (sql/execute! db-spec ["DELETE FROM vars WHERE version_id = ?" version-id])
  (sql/execute! db-spec ["DELETE FROM namespaces WHERE version_id = ?" version-id]))

(defn- get-version-id
  [db-spec group-id artifact-id version-name]
  {:pre [(and group-id artifact-id version-name)]}
  (first
   (sql/query db-spec
              ["select id from versions where group_id = ? and artifact_id = ? and name = ?"
               group-id artifact-id version-name]
              {:row-fn :id})))

(defn- get-documented-versions-by-group-id
  "Get all known versions that also have some metadata (usually means that they have documentation)" ;TODO use build_id / merge with releases table?
  [db-spec group-id]
  (sql/query db-spec ["select group_id, artifact_id, name from versions where group_id = ? and meta not null" group-id]))

(defn- get-version [db-spec version-id]
  (first (sql/query db-spec ["select meta from versions where id = ?" version-id]
                    {:row-fn (fn [r] (some-> r :meta nippy/thaw))})))

;; TODO We currently store various fields in metadata and the database table
;; this is probably a bad idea as it might lead to inconsistencies and because
;; we would have to manually verify conformance of blob data compared to
;; non-null columns where conformance is ensured on insert

(defn- get-namespaces [db-spec version-id]
  (sql/query db-spec ["select name, meta from namespaces where version_id = ?" version-id]
             {:row-fn (fn [r]

                        (-> r :meta nippy/thaw (assoc :name (:name r))))}))

(defn- get-vars [db-spec version-id]
  (sql/query db-spec ["select name, meta from vars where version_id = ?" version-id]
             {:row-fn (fn [r]
                        (assert (-> r :meta nippy/thaw :namespace)
                                (format "namespace missing from meta"))
                        (-> r :meta nippy/thaw (assoc :name (:name r))))}))

(defn- docs-cache-contents [db-spec version-id]
  {:version    (p :get-version (or (get-version db-spec version-id) {}))
   :group      {}
   :artifact   {}
   :namespaces (p :get-namespaces (set (get-namespaces db-spec version-id)))
   :defs       (p :get-vars (set (get-vars db-spec version-id)))})

;; API --------------------------------------------------------------------------

(defn docs-available? [db-spec group-id artifact-id version-name]
  (boolean
   (first
    (sql/query db-spec
               ["select id from versions where group_id = ? and artifact_id = ? and name = ? and meta not null";TODO use build_id / merge with releases table?
                group-id artifact-id version-name]
               {:row-fn :id}))))

(defn bundle-docs
  [db-spec {:keys [group-id artifact-id version] :as v}]
  (if-let [version-id (get-version-id db-spec group-id artifact-id version)]
    (->> {:cache-contents (docs-cache-contents db-spec version-id)
          :cache-id       {:group-id group-id, :artifact-id artifact-id, :version version}}
         (cljdoc.spec/assert :cljdoc.spec/cache-bundle))
    (throw (Exception. (format "Could not find version %s" v)))))

(defn bundle-group
  [db-spec group-id]
  (let [versions (p :get-documented-versions-by-group-id
                    (get-documented-versions-by-group-id db-spec group-id))]
    (->> {:cache-contents {:versions  (for [v versions]
                                        {:artifact-id (:artifact_id v)
                                         :version     (:name v)})
                           :artifacts (set (map :artifact_id versions))}
          :cache-id       {:group-id  group-id}}
         (cljdoc.spec/assert :cljdoc.spec/cache-bundle))))

(defn import-api [db-spec
                  {:keys [group-id artifact-id version]}
                  codox]
  (let [version-id (get-version-id db-spec group-id artifact-id version)]
    (log/info "version-id" version-id)
    (clear-vars-and-nss! db-spec version-id)
    (sql/with-db-transaction [tx db-spec]
      (doseq [ns (for [[platf namespaces] codox
                       ns namespaces]
                   (-> (dissoc ns :publics)
                       (update :name name)
                       (assoc :platform platf)))]
        (write-ns! tx version-id ns))
      (let [vars (for [[platf namespaces] codox
                       ns namespaces
                       var (:publics ns)]
                   (assoc var
                          :namespace (name (:name ns))
                          :name (name (:name var))
                          :platform platf))]
        (doseq [var (set vars)]
          (write-var! tx version-id var))))))

(defn import-doc [db-spec
                  {:keys [group-id artifact-id version]}
                  {:keys [doc-tree scm jar]}]
  {:pre [(string? group-id) (string? artifact-id) (string? version)]}
  (store-artifact! db-spec group-id artifact-id [version])
  (let [version-id (get-version-id db-spec group-id artifact-id version)]
    (update-version-meta! db-spec version-id {:jar jar :scm scm, :doc doc-tree})))

(comment
  (def data (clojure.edn/read-string (slurp "https://2941-119377591-gh.circle-artifacts.com/0/cljdoc-edn/stavka/stavka/0.4.1/cljdoc.edn")))

  (clojure.pprint/pprint (:codox data))

  (get (:codox data) "clj")
  (for [[platf namespaces] (:codox data)]
    (println namespaces))

  (for [[platf namespaces] (:codox data)
        ns namespaces]
    (-> (dissoc ns :publics)
        (assoc :platform platf)))

  (import-api db-spec
              (select-keys data [:group-id :artifact-id :version])
              (:codox data))

  (store-artifact! db-spec (:group-id data) (:artifact-id data) [(:version data)])

  (get-version-id db-spec (:group-id data) (:artifact-id data) (:version data))

  (tufte/add-basic-println-handler! {})

  (def db-spec
    {:classname "org.sqlite.JDBC"
     :subprotocol "sqlite"
     :foreign_keys true
     :synchronous "NORMAL"
     :journal_mode "WAL"
     :cache_size 10000
     :subname "test-data/build-log.db"})

  (sql/query db-spec ["PRAGMA main.synchronous"])
  (sql/query db-spec ["PRAGMA main.journal_mode"])

  (first (get-versions-by-group-id db-spec "amazonica"))

  (bundle-docs db-spec {:group-id "beam" :artifact-id "beam-es" :version "0.0.1"})

  (profile {} (doseq [i (range 50)] (bundle-group db-spec "amazonica")))

  (profile {}
           (doseq [i (range 50)]
             (bundle-docs db-spec {:group-id "lt.tokenmill" :artifact-id "es-utils" :version "0.1.2"})))

  (profile {}
           (doseq [i (range 50)]
             (bundle-docs db-spec {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"})))

  (profile {}
           (doseq [i (range 50)]
             (bundle-docs db-spec {:group-id "amazonica" :artifact-id "amazonica" :version "0.3.130"})))

  (sql/query #_(assoc
              (cljdoc.config/build-log-db)
              :cache_size 10000)
(cljdoc.config/build-log-db)
             ["PRAGMA main.cache_size"])

  )

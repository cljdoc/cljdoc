(ns cljdoc.storage.sqlite-impl
  "Implementation details for the storage layer as defined in [[cljdoc.storage]].

  #### The cljdoc domain model

  For each artifact that exists we store one row in the `versions` table. This row has one
  column `meta` which stores a blob of information related to this artifact. This includes
  information about the Git repository, the cljdoc configuration file and so on.

  Similarly there are `namespaces` and `vars` tables that store namespaces and vars and link
  them back to a single artifact via a `version_id`. The `version_id` is the `ROWID` of an
  entry in the `versions` table. The `namespaces` and `vars` tables also each have a `meta`
  column which is used to store most of the information in a schema-less manner.

  All `meta` columns are serialized with [Nippy](https://github.com/ptaoussanis/nippy).

  #### JDBC, HUGSQL, SQLite

  For most of the time this namespace only used raw `clojure.java.jdbc` functions since queries
  were basic. At some point the need for more complex queries arose [1] and HUGSQL was added
  to the mix."
  (:require [cljdoc.spec]
            [cljdoc.util :as util]
            [cljdoc.user-config :as user-config]
            [clojure.set :as cset]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [taoensso.nippy :as nippy]
            [taoensso.tufte :as tufte :refer [defnp p profiled profile]]
            [version-clj.core :as version-clj]
            [hugsql.core :as hugsql])
  (:import (org.sqlite SQLiteException)))

(hugsql/def-db-fns "sql/sqlite_impl.sql")
;; (hugsql/def-sqlvec-fns "sql/sqlite_impl.sql")

;; Writing ----------------------------------------------------------------------

(defn store-artifact! [db-spec group-id artifact-id versions]
  (assert (coll? versions))
  (doseq [v versions]
    (sql/execute! db-spec ["INSERT OR IGNORE INTO versions (group_id, artifact_id, name) VALUES (?, ?, ?)" group-id artifact-id v])))

(defn- update-version-meta! [db-spec version-id data]
  (sql/execute! db-spec ["UPDATE versions SET meta = ? WHERE id = ?" (nippy/freeze data) version-id]))

(defn- write-ns!
  [db-spec version-id {:keys [platform name] :as data}]
  {:pre [(string? name) (string? platform)]}
  (try
    (sql/execute! db-spec ["INSERT INTO namespaces (version_id, platform, name, meta) VALUES (?, ?, ?, ?)"
                           version-id platform name (nippy/freeze data)])
    (catch SQLiteException e
      (throw (ex-info (format "Failed to insert namespace %s" data)
                      {:var data}
                      e)))))

(defn- write-var!
  [db-spec version-id {:keys [platform namespace name] :as data}]
  {:pre [(string? name) (string? namespace) (string? platform)]}
  (try
    (sql/execute! db-spec ["INSERT INTO vars (version_id, platform, namespace, name, meta) VALUES (?, ?, ?, ?, ?)"
                           version-id platform namespace name (nippy/freeze data)])
    (catch SQLiteException e
      (throw (ex-info (format "Failed to insert var %s" data)
                      {:var data}
                      e)))))

(defn clear-vars-and-nss! [db-spec version-id]
  (sql/execute! db-spec ["DELETE FROM vars WHERE version_id = ?" version-id])
  (sql/execute! db-spec ["DELETE FROM namespaces WHERE version_id = ?" version-id]))

;; Reading ----------------------------------------------------------------------

(defnp ^:private get-version-id
  [db-spec group-id artifact-id version-name]
  {:pre [(and group-id artifact-id version-name)]}
  (first
   (sql/query db-spec
              ["select id from versions where group_id = ? and artifact_id = ? and name = ?"
               group-id artifact-id version-name]
              {:row-fn :id})))

(defnp ^:private get-version [db-spec version-id]
  (first (sql/query db-spec ["select meta from versions where id = ?" version-id]
                    {:row-fn (fn [r] (some-> r :meta nippy/thaw))})))

(defn- version-row-fn [r]
  (cset/rename-keys r {:group_id :group-id, :artifact_id :artifact-id, :name :version}))

(defnp ^:private resolve-version-ids [db-spec version-entities]
  (let [v-tuples (map (fn [v] [(:group-id v) (:artifact-id v) (:version v)]) version-entities)]
    (sql-resolve-version-ids db-spec {:version-entities v-tuples} {} {:row-fn version-row-fn})))

;; TODO We currently store various fields in metadata and the database table
;; this is probably a bad idea as it might lead to inconsistencies and because
;; we would have to manually verify conformance of blob data compared to
;; non-null columns where conformance is ensured on insert

(defnp ^:private get-namespaces [db-spec resolved-versions]
  (let [id-indexed (util/index-by :id resolved-versions)]
    (->> (sql-get-namespaces db-spec {:version-ids (map :id resolved-versions)})
         ;; Match up version entities so each namespace can be linked back to it's artifact
         (map (fn [r] (assoc r :version-entity (get id-indexed (:version_id r)))))
         ;; Deserialize nippy, add name and version entity to map
         (map (fn [r] (merge (-> r :meta nippy/thaw) (select-keys r [:name :version-entity])))))))

(defnp ^:private vars-row-fn [r]
  (assert (-> r :meta nippy/thaw :namespace)
          (format "namespace missing from meta"))
  (-> r :meta nippy/thaw (assoc :name (:name r))))

(defnp ^:private get-vars [db-spec namespaces-with-resolved-version-entities]
  (let [ns-idents (->> namespaces-with-resolved-version-entities
                      (map (fn [ns] [(-> ns :version-entity :id) (:name ns)])))]
    (assert (seq ns-idents))
    (sql-get-vars db-spec {:ns-idents ns-idents}
                  {}
                  {:row-fn vars-row-fn})))

(defn- sql-exists?
  "A small helper to deal with the complex keys that sqlite returns for exists queries."
  [db-spec sqlvec]
  (case (val (first (first (sql/query db-spec sqlvec))))
    0 false
    1 true))

;; API --------------------------------------------------------------------------

(defn get-documented-versions
  "Get all known versions that also have some metadata (usually means that they have documentation)"
  ;; TODO use build_id / merge with releases table?
  ([db-spec group-id]
   {:pre [(string? group-id)]}
   (sql/query db-spec ["select group_id, artifact_id, name from versions where group_id = ? and meta not null" group-id] {:row-fn version-row-fn}))
  ([db-spec group-id artifact-id]
   {:pre [(string? group-id) (string? artifact-id)]}
   (sql/query db-spec ["select group_id, artifact_id, name from versions where group_id = ? and artifact_id = ? and meta not null" group-id artifact-id] {:row-fn version-row-fn})))

(defnp latest-release-version [db-spec {:keys [group-id artifact-id]}]
  (->> (get-documented-versions db-spec group-id artifact-id)
       (map :version)
       (remove #(.endsWith % "-SNAPSHOT"))
       (version-clj/version-sort)
       (last)))

(defn all-distinct-docs [db-spec]
  (sql/query db-spec ["select group_id, artifact_id, name from versions"] {:row-fn version-row-fn}))

(defn docs-available? [db-spec group-id artifact-id version-name]
  (or (sql-exists? db-spec ["select exists(select id from versions where group_id = ? and artifact_id = ? and name = ? and meta not null)"
                            group-id artifact-id version-name])
      ;; meta should always be set to at least {} but hasn't been set for a while.
      ;; this is a temporary fix for this that should get revisited when switching
      ;; the order of GIT vs API imports.
      (let [v-id (get-version-id db-spec group-id artifact-id version-name)]
        (sql-exists? db-spec ["select exists(select id from vars where version_id = ?)" v-id]))))

(defn bundle-docs
  "Bundles the documentation for a particular artifact.

  If `dependency-version-entities` are provided namespaces and vars from those dependencies
  will also be included which was mainly added to support module-based libraries.

  **Note:** When rendering namespaces we ultimately need some information about the backing Git
  repository to create proper source links. For now this is only supported if the modules
  included via `dependency-version-entities`are backed by the same Git repository as the primary
  artifact."
  [db-spec {:keys [group-id artifact-id version dependency-version-entities] :as v}]
  (let [primary-version-entity (select-keys v [:group-id :artifact-id :version])
        resolved-versions      (resolve-version-ids db-spec (conj dependency-version-entities primary-version-entity))
        primary-resolved       (first (filter #(= primary-version-entity (dissoc % :id)) resolved-versions))]
    (if-not primary-resolved
      (throw (Exception. (format "Could not find version %s" v)))
      (let [version-data (or (get-version db-spec (:id primary-resolved)) {})
            include-cfg  (user-config/include-namespaces-from-deps (:config version-data) (util/clojars-id v))
            wanted?      (set (map util/normalize-project include-cfg))
            extra-deps   (filter #(wanted? (str (:group-id %) "/" (:artifact-id %))) resolved-versions)
            namespaces   (set (get-namespaces db-spec (conj extra-deps primary-resolved)))]
        (-> {:version    version-data
             :namespaces namespaces
             ;; NOTE maybe we should only load defs for a subset of namespaces
             :defs       (if (seq namespaces)
                           (set (get-vars db-spec namespaces))
                           #{})
             :latest (latest-release-version db-spec v)
             :version-entity {:group-id group-id
                              :artifact-id artifact-id
                              :version version}})))))

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
                  {:keys [jar scm doc-tree config]}]
  {:pre [(string? group-id) (string? artifact-id) (string? version)]}
  (store-artifact! db-spec group-id artifact-id [version])
  (let [version-id (get-version-id db-spec group-id artifact-id version)]
    (update-version-meta! db-spec version-id {:jar jar :scm scm, :doc doc-tree, :config config})))

(comment
  (def data (clojure.edn/read-string (slurp "https://2941-119377591-gh.circle-artifacts.com/0/cljdoc-edn/stavka/stavka/0.4.1/cljdoc.edn")))

  (def db-spec
   (cljdoc.config/db (cljdoc.config/config)))

  (all-distinct-docs db-spec)

  (import-api db-spec
              (select-keys data [:group-id :artifact-id :version])
              (:codox data))

  (store-artifact! db-spec (:group-id data) (:artifact-id data) [(:version data)])

  (get-version-id db-spec (:group-id data) (:artifact-id data) (:version data)))



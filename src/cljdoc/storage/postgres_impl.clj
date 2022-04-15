(ns cljdoc.storage.postgres-impl
  "Postgres implementation of the abstract storage interface defined in [[cljdoc.storage]].
  This namespace only contains the Postgres specific parts of the implementation and requires the
  DB-agnostic parts from `cljdoc.storage.db-commons`."
  (:require [cljdoc.storage.api :refer [IStorage]]
            [cljdoc.storage.db-commons :as db]
            [cljdoc-shared.spec.analyzer :as analyzer-spec]
            [clojure.java.jdbc :as sql]))

(defn- sql-exists?
  "A small helper to deal nested Postgres returns for exists queries."
  [db-spec sqlvec]
  (val (first (first (sql/query db-spec sqlvec)))))

(defn store-artifact! [db-spec group-id artifact-id versions]
  (assert (coll? versions))
  (doseq [v versions]
    (sql/execute! db-spec ["INSERT INTO versions (group_id, artifact_id, name) VALUES (?, ?, ?) ON CONFLICT DO NOTHING" group-id artifact-id v])))

(defrecord PostgresStorage [db-spec]
  IStorage
  (import-api [_ version-entity api-analysis]
    (analyzer-spec/assert-result-namespaces api-analysis)
    (store-artifact! db-spec
                     (:group-id version-entity)
                     (:artifact-id version-entity)
                     [(:version version-entity)])
    (db/import-api db-spec version-entity api-analysis))
  (import-doc [_ {:keys [group-id artifact-id version]} {:keys [doc-tree scm jar config]}]
    {:pre [(string? group-id) (string? artifact-id) (string? version)]}
    (store-artifact! db-spec group-id artifact-id [version])
    (let [version-id (db/get-version-id db-spec group-id artifact-id version)]
      (db/update-version-meta! db-spec version-id {:jar jar :scm scm, :doc doc-tree, :config config})))
  (exists? [_ version-entity]
    (db/docs-available? db-spec
                        sql-exists?
                        (:group-id version-entity)
                        (:artifact-id version-entity)
                        (:version version-entity)))
  (bundle-docs [_ version-entity]
    (db/bundle-docs db-spec version-entity))
  (list-versions [_ group-id]
    (db/get-documented-versions db-spec group-id))
  (all-distinct-docs [_]
    (db/all-distinct-docs db-spec)))


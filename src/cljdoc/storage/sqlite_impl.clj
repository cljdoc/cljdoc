(ns cljdoc.storage.sqlite-impl
  "SQLite implementation of the abstract storage interface defined in [[cljdoc.storage]].
  This namespace only contains the SQLite specific parts of the implementation and requires the
  DB-agnostic parts from `cljdoc.storage.db-commons`.

   #### HUGSQL
  The more complex SQL queries are defined via HUGSQL and can be found in `sql/sqlite_impl.sql`"
  (:require [clojure.java.jdbc :as sql]
            [cljdoc.storage.api :refer [IStorage]]
            [cljdoc.storage.db-commons :as db]
            [cljdoc-shared.spec.analyzer :as analyzer-spec]
            [hugsql.core :as hugsql]))

;; keep our linter happy by declaring hugsql imported functions
(declare sql-resolve-version-ids)
(declare sql-get-namespaces)
(declare sql-get-vars)

(hugsql/def-db-fns "sql/sqlite_impl.sql")

(defn- sql-exists?
  "A small helper to deal with the complex keys that sqlite returns for exists queries."
  [db-spec sqlvec]
  (case (val (first (first (sql/query db-spec sqlvec))))
    0 false
    1 true))

(defn store-artifact! [db-spec group-id artifact-id versions]
  (db/store-artifact!
   db-spec
   ["INSERT OR IGNORE INTO versions (group_id, artifact_id, name) VALUES (?, ?, ?)" group-id artifact-id]
   versions))

(defrecord SQLiteStorage [db-spec]
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
    (db/bundle-docs db-spec version-entity {:sql-resolve-version-ids sql-resolve-version-ids
                                            :sql-get-namespaces      sql-get-namespaces
                                            :sql-get-vars            sql-get-vars}))
  (list-versions [_ group-id]
    (db/get-documented-versions db-spec group-id))
  (all-distinct-docs [_]
    (db/all-distinct-docs db-spec)))

(comment
  ;; Note to reader: if this link still worked, it would reference new naming convention: .../cljdoc-analysis-edn/.../cljdoc-analysis.edn
  (def data (clojure.edn/read-string (slurp "https://2941-119377591-gh.circle-artifacts.com/0/cljdoc-edn/stavka/stavka/0.4.1/cljdoc.edn")))

  (def db-spec
    (cljdoc.config/sqlite (cljdoc.config/config)))

  (all-distinct-docs db-spec)

  (import-api db-spec
              (select-keys data [:group-id :artifact-id :version])
              (:codox data))

  (store-artifact! db-spec (:group-id data) (:artifact-id data) [(:version data)])

  (get-version-id db-spec (:group-id data) (:artifact-id data) (:version data)))

(ns cljdoc.storage.api
  "A protocol to abstract storage over cljdoc's storage backend.

  This has been put into place when migrating from one storage
  backend to another and now largely puts some constraints on how
  the database is accessed, making access more structured.

  The basic datamodel is Group > Artifact > Version. Namespaces
  and vars are linked against versions."
  (:require [cljdoc-shared.proj :as proj]
            [cljdoc-shared.spec.analyzer :as analyzer-spec]
            [cljdoc.storage.sqlite-impl :as sqlite]))

(defprotocol IStorage
  (import-api [_ version-entity codox])
  (import-doc [_ version-entity {:keys [doc-tree scm scm-articles jar config]}])
  (exists? [_ entity])
  (bundle-docs [_ version-entity])
  (list-versions [_ group-id])
  (all-distinct-docs [_]))

(defrecord SQLiteStorage [db-spec]
  IStorage
  (import-api [_ version-entity api-analysis]
    (analyzer-spec/assert-result-namespaces api-analysis)
    (sqlite/store-artifact! db-spec
                            (:group-id version-entity)
                            (:artifact-id version-entity)
                            [(:version version-entity)])
    (sqlite/import-api db-spec version-entity api-analysis))
  (import-doc [_ version-entity {:keys [doc-tree scm scm-articles jar config] :as version-data}]
    (sqlite/import-doc db-spec version-entity version-data))
  (exists? [_ version-entity]
    (sqlite/docs-available? db-spec
                            (:group-id version-entity)
                            (:artifact-id version-entity)
                            (:version version-entity)))
  (bundle-docs [_ {:keys [group-id artifact-id version] :as version-entity}]
    (sqlite/bundle-docs db-spec version-entity))
  (list-versions [_ group-id]
    (sqlite/get-documented-versions db-spec group-id))
  (all-distinct-docs [_]
    (sqlite/all-distinct-docs db-spec)))

(defn version-entity [project version]
  {:group-id (proj/group-id project)
   :artifact-id (proj/artifact-id project)
   :version version})

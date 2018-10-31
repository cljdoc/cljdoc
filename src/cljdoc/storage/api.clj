(ns cljdoc.storage.api
  (:require [cljdoc.spec :as cljdoc-spec]
            [cljdoc.storage.sqlite-impl :as sqlite]))

(defprotocol IStorage
  (import-api [_ version-entity codox])
  (import-doc [_ version-entity {:keys [doc-tree scm jar]}])
  (exists? [_ entity])
  (bundle-docs [_ version-entity])
  (bundle-group [_ group-entity])
  (all-distinct-docs [_])
  )

(defrecord SQLiteStorage [db-spec]
  IStorage
  (import-api [_ version-entity codox]
    (cljdoc-spec/assert :cljdoc.cljdoc-edn/codox codox)
    (sqlite/store-artifact! db-spec
                            (:group-id version-entity)
                            (:artifact-id version-entity)
                            [(:version version-entity)])
    (sqlite/import-api db-spec version-entity codox))
  (import-doc [_ version-entity {:keys [doc-tree scm jar] :as version-data}]
    (sqlite/import-doc db-spec version-entity version-data))
  (exists? [_ version-entity]
    (sqlite/docs-available? db-spec
                            (:group-id version-entity)
                            (:artifact-id version-entity)
                            (:version version-entity)))
  (bundle-docs [_ {:keys [group-id artifact-id version] :as version-entity}]
    (sqlite/bundle-docs db-spec version-entity))
  (bundle-group [_ {:keys [group-id]}]
    (sqlite/bundle-group db-spec group-id))
  (all-distinct-docs [_]
    (sqlite/all-distinct-docs db-spec)))

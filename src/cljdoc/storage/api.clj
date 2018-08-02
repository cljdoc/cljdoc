(ns cljdoc.storage.api
  (:require [cljdoc.spec :as cljdoc-spec]
            [cljdoc.storage.grimoire-impl :as grim]
            [cljdoc.storage.sqlite-impl :as sqlite]))

(defprotocol IStorage
  (import-api [_ version-entity codox])
  (import-doc [_ version-entity {:keys [doc-tree scm jar]}])
  (exists? [_ entity])
  (bundle-docs [_ version-entity])
  (bundle-group [_ group-entity])
  )

(defrecord GrimoireStorage [dir]
  IStorage
  (import-api [_ version-entity codox]
    (cljdoc-spec/assert :cljdoc.cljdoc-edn/codox codox)
    (let [store     (grim/grimoire-store dir)
          version-t (grim/thing (:group-id version-entity)
                                (:artifact-id version-entity)
                                (:version version-entity))]
      (grim/write-bare store version-t)
      (grim/import-api
       {:store store, :version version-t, :codox codox})))
  (import-doc [_ version-entity {:keys [doc-tree scm jar]}]
    (grim/import-doc
     {:store   (grim/grimoire-store dir)
      :version (grim/thing (:group-id version-entity)
                           (:artifact-id version-entity)
                           (:version version-entity))
      :doc-tree doc-tree
      :scm scm
      :jar jar}))
  (exists? [_ version-entity]
    (grim/exists?
     (grim/grimoire-store dir)
     (grim/thing (:group-id version-entity)
                 (:artifact-id version-entity)
                 (:version version-entity))))
  (bundle-docs [_ {:keys [group-id artifact-id version]}]
    (-> (grim/grimoire-store dir)
        (grim/bundle-docs (grim/thing group-id artifact-id version))))
  (bundle-group [_ {:keys [group-id]}]
    (-> (grim/grimoire-store dir)
        (grim/bundle-group (grim/thing group-id)))))

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
    (sqlite/docs-available? (:group-id version-entity)
                            (:artifact-id version-entity)
                            (:version version-entity)))
  (bundle-docs [_ {:keys [group-id artifact-id version] :as version-entity}]
    (sqlite/bundle-docs db-spec version-entity))
  (bundle-group [_ {:keys [group-id]}]
    (sqlite/bundle-group db-spec group-id)))

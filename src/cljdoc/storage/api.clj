(ns cljdoc.storage.api
  (:require [cljdoc.storage.grimoire-impl :as grim]))

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

(comment
  (defprotocol ITest
    (exists? [_ x]))

  (defrecord TestImpl []
    ITest
    (exists? [_ x] false))
  )

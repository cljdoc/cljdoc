(ns cljdoc.storage.api
  (:require [cljdoc.grimoire-helpers :as grimoire-helpers]
            [cljdoc.storage.grimoire-impl :as grim-retrieve]))

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
    (let [store     (grimoire-helpers/grimoire-store dir)
          version-t (grimoire-helpers/thing (:group-id version-entity)
                                            (:artifact-id version-entity)
                                            (:version version-entity))]
      (grimoire-helpers/write-bare store version-t)
      (grimoire-helpers/import-api
       {:store store, :version version-t, :codox codox})))
  (import-doc [_ version-entity {:keys [doc-tree scm jar]}]
    (grimoire-helpers/import-doc
     {:store   (grimoire-helpers/grimoire-store dir)
      :version (grimoire-helpers/thing (:group-id version-entity)
                                       (:artifact-id version-entity)
                                       (:version version-entity))
      :doc-tree doc-tree
      :scm scm
      :jar jar}))
  (exists? [_ version-entity]
    (grimoire-helpers/exists?
     (grimoire-helpers/grimoire-store dir)
     (grimoire-helpers/thing (:group-id version-entity)
                             (:artifact-id version-entity)
                             (:version version-entity))))
  (bundle-docs [_ {:keys [group-id artifact-id version]}]
    (-> (grimoire-helpers/grimoire-store dir)
        (grim-retrieve/bundle-docs (grimoire-helpers/thing group-id artifact-id version))))
  (bundle-group [_ {:keys [group-id]}]
    (-> (grimoire-helpers/grimoire-store dir)
        (grim-retrieve/bundle-group (grimoire-helpers/thing group-id)))))

(comment
  (defprotocol ITest
    (exists? [_ x]))

  (defrecord TestImpl []
    ITest
    (exists? [_ x] false))
  )

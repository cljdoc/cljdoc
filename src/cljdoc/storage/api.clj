(ns cljdoc.storage.api
  "A protocol to abstract storage over cljdoc's storage backend.

  This has been put into place when migrating from one storage
  backend to another and now largely puts some constraints on how
  the database is accessed, making access more structured.

  The basic datamodel is Group > Artifact > Version. Namespaces
  and vars are linked against versions."
  (:require [cljdoc-shared.proj :as proj]))

(defprotocol IStorage
  (import-api [_ version-entity codox])
  (import-doc [_ version-entity {:keys [doc-tree scm jar]}])
  (exists? [_ entity])
  (bundle-docs [_ version-entity])
  (list-versions [_ group-id])
  (all-distinct-docs [_]))

(defn version-entity [project version]
  {:group-id (proj/group-id project)
   :artifact-id (proj/artifact-id project)
   :version version})

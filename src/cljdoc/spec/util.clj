(ns cljdoc.spec.util
  (:require [cljdoc.render.api-searchset :as api-searchset]
            [cljdoc.server.pedestal :as server.pedestal]
            [clojure.string :as str]
            [integrant.repl.state :as state]
            [malli.provider]))

(defn format-version-entity
  "Trickery to handle when a string version-entity is missing a group-id."
  ([group-and-artifact-id version]
   (format-version-entity group-and-artifact-id
                          group-and-artifact-id
                          version))
  ([group-id artifact-id version]
   {:group-id group-id
    :artifact-id artifact-id
    :version version}))

(defn parse-version-entity
  "Parse the version entity if it is a string, and just return it if it's not."
  [version-entity]
  (if (string? version-entity)
    (apply format-version-entity (str/split version-entity #"/"))
    version-entity))

(defn load-pom
  "Load pom info for an analyzed docset."
  [version-entity]
  (let [version-entity (parse-version-entity version-entity)
        cache (:cljdoc/cache state/system)
        get-pom-xml (:cljdoc.util.repositories/get-pom-xml cache)]
    (server.pedestal/load-pom get-pom-xml version-entity)))

(defn load-cache-bundle
  "Load the cache-bundle for the analyzed docset."
  [version-entity]
  (let [version-entity (parse-version-entity version-entity)
        storage (:cljdoc/storage state/system)
        pom-info (load-pom version-entity)]
    (server.pedestal/load-cache-bundle storage pom-info version-entity)))

(defn load-searchset
  "Load the searchset for the analyzed docset."
  [version-entity]
  (-> version-entity
      load-cache-bundle
      api-searchset/cache-bundle->searchset))

(comment

  (require '[cljdoc.spec.cache-bundle :as cbs]
           '[cljdoc.spec.searchset :as ss])

  (def version-entities
    ["org.cljdoc/cljdoc-exerciser/1.0.77"
     "net.cgrand/xforms/0.19.2"
     "meander/epsilon/0.0.650"
     "methodical/methodical/0.12.2"
     "com.wsscode/pathom3/2022.03.17-alpha"
     "com.rpl/specter/1.1.4"
     "cli-matic/cli-matic/0.5.1"
     "prismatic/schema/1.2.0"
     "com.fulcrologic/fulcro/3.5.15"
     "fulcrologic/fulcro/2.8.13"
     "metosin/reitit/0.5.17"
     "compojure/compojure/1.6.2"
     "luminus-db/luminus-db/0.1.1"
     "rum/rum/0.12.9"])
  (load-pom (first version-entities))

  (let [cache-bundle (load-cache-bundle (first version-entities))]
    (cbs/valid? cache-bundle))

  (let [cache-bundle (load-cache-bundle (first version-entities))]
    (cbs/explain
     (assoc cache-bundle :version :vInfinity)))

  (let [cache-bundle (load-cache-bundle (first version-entities))]
    (cbs/explain-humanized
     (assoc cache-bundle :version :vInfinity)))

  (let [searchset (load-searchset (first version-entities))]
    (ss/explain-humanized
     (assoc searchset :namespaces :bad-value)))

  )

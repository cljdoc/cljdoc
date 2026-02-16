(ns cljdoc.spec.util
  "Helpful repl support for playing with malli specs"
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
        pom-fetcher (:cljdoc/cached-pom-fetcher state/system)]
    (server.pedestal/load-pom pom-fetcher version-entity)))

(defn load-docset
  "Load the docset for the analyzed docset."
  [version-entity]
  (let [version-entity (parse-version-entity version-entity)
        storage (:cljdoc/storage state/system)
        pom-info (load-pom version-entity)]
    (server.pedestal/load-docset storage pom-info version-entity)))

(defn load-searchset
  "Load the searchset for the analyzed docset."
  [version-entity]
  (-> version-entity
      load-docset
      api-searchset/docset->searchset))

(comment

  (require '[cljdoc.spec.docset :as dss]
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

  ;; TIP: startup system first via cljdoc.server.system, see its comment block

  (load-pom (first version-entities))

  (let [docset (load-docset (first version-entities))]
    (dss/valid? docset))
  ;; => true

  (let [docset (load-docset (first version-entities))]
    (dss/explain
     (assoc docset :version :vInfinity)))

  (let [docset (load-docset (first version-entities))]
    (dss/explain-humanized
     (assoc docset :version :vInfinity)))
  ;; => {:version ["invalid type"]}

  (let [searchset (load-searchset (first version-entities))]
    (dss/explain-humanized
     (assoc searchset :namespaces :bad-value)))
  ;; => Oct 22, 2025 5:07:19 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/convert.rb convert
  ;;    INFO: possible invalid reference: custom-themes
  ;;    Oct 22, 2025 5:07:19 P.M.
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Oct 22, 2025 5:07:19 P.M.
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Oct 22, 2025 5:07:19 P.M.
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Oct 22, 2025 5:07:19 P.M.
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    {:version ["missing required key"],
  ;;     :namespaces ["invalid type"],
  ;;     :defs ["invalid type"],
  ;;     :latest ["missing required key"],
  ;;    :version-entity ["missing required key"]}

  :eoc)

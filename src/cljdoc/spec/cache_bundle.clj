(ns cljdoc.spec.cache-bundle
  "Schema and related functions for the cache-bundle structure
  generated via `cljdoc.storage.sqlite-impl/bundle-docs`."
  (:require [malli.core :as malli]
            [malli.error]
            [malli.provider]))

(def schema
  (let [doc-attrs
        [:map
         [:cljdoc.doc/source-file {:optional true} string?]
         [:cljdoc/markdown {:optional true} string?]
         [:cljdoc.doc/type {:optional true} qualified-keyword?]
         [:slug string?]
         [:cljdoc.doc/contributors {:optional true} [:sequential string?]]
         [:cljdoc/asciidoc {:optional true} string?]]

        arglists [:sequential [:vector any?]]]
    [:map
     [:version
      [:map
       [:jar [:maybe [:map]]]
       [:scm
        [:maybe
         [:map
          [:files [:map-of string? string?]]
          [:rev string?]
          [:branch string?]
          [:tag {:optional true} [:map-of keyword? string?]]
          [:url string?]
          [:commit string?]]]]
       [:doc
        [:maybe
         [:vector
          [:map
           [:title string?]
           [:attrs doc-attrs]
           [:children
            {:optional true}
            [:vector
             [:map
              [:title string?]
              [:attrs doc-attrs]]]]]]]]
       [:config
        [:maybe
         [:map
          [:cljdoc.doc/tree
           [:vector
            [:vector
             [:or
              string?
              [:map [:file {:optional true} string?]]
              [:vector [:or string? [:map [:file string?]]]]]]]]
          [:cljdoc/include-namespaces-from-dependencies
           {:optional true}
           [:vector symbol?]]]]]]]
     [:namespaces
      [:set
       [:map
        [:name string?]
        [:doc {:optional true} string?]
        [:platform string?]
        [:version-entity
         [:map
          [:id int?]
          [:group-id string?]
          [:artifact-id string?]
          [:version string?]]]
        [:author {:optional true} string?]
        [:deprecated {:optional true} string?]]]]
     [:defs
      [:set
       [:map
        [:name string?]
        [:file string?]
        [:type keyword?]
        [:dynamic {:optional true} boolean?]
        [:line int?]
        [:deprecated {:optional true} some?]
        [:members
         {:optional true}
         [:sequential
          [:map
           [:name symbol?]
           [:arglists arglists]
           [:doc {:optional true} string?]
           [:type keyword?]]]]
        [:arglists {:optional true} arglists]
        [:doc {:optional true} string?]
        [:namespace string?]
        [:platform string?]]]]
     [:latest string?]
     [:version-entity [:map-of keyword? string?]]]))

(def valid?
  "Given a cache-bundle structure, return true if valid or false if not."
  (malli/validator schema))

(def explain
  "Given a cache-bundle structure, return the explanation for the validation."
  (malli/explainer schema))

(defn explain-humanized
  "Given a cache-bundle structure, return the humanized explanation for the validation."
  [cache-bundle]
  (malli.error/humanize (explain cache-bundle)))

(comment

  (require '[cljdoc.server.pedestal :as server.pedestal]
           '[clojure.string :as str]
           '[integrant.repl.state :as state]
           '[malli.provider])

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
    (valid? cache-bundle))

  (let [cache-bundle (load-cache-bundle (first version-entities))]
    (explain
     (assoc cache-bundle :version :vInfinity)))

  (let [cache-bundle (load-cache-bundle (first version-entities))]
    (explain-humanized
     (assoc cache-bundle :version :vInfinity)))

  ;; infer a schema to get you started
  (malli.provider/provide (mapv load-cache-bundle version-entities))

  )

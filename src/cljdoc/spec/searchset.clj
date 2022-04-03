(ns cljdoc.spec.searchset
  "Schema and related functions for the searchset structure
  generated via `cljdoc.render.api-searchset/cache-bundle->searchset`."
  (:require [malli.core :as malli]
            [malli.error]
            [malli.provider]))

(def schema
  [:map
   [:namespaces [:vector
                 [:map
                  [:name string?]
                  [:path string?]
                  [:platform string?]
                  [:doc {:optional true} string?]]]]
   [:defs [:vector
           [:map
            [:namespace string?]
            [:name string?]
            [:type keyword?]
            [:path string?]
            [:platform string?]
            [:doc {:optional true} string?]
            [:arglists {:optional true} [:sequential [:vector any?]]]
            [:members
             [:sequential
              [:map
               [:type keyword?]
               [:name symbol?]
               [:arglists [:sequential [:vector any?]]]
               [:doc {:optional true} string?]]]]]]]
   [:docs [:vector
           [:map
            [:name string?]
            [:path string?]
            [:doc string?]]]]])

(def valid?
  "Given a searchset structure, return true if valid or false if not."
  (malli/validator schema))

(def explain
  "Given a searchset structure, return the explanation for the validation."
  (malli/explainer schema))

(defn explain-humanized
  "Given a searchset structure, return the humanized explanation for the validation."
  [searchset]
  (malli.error/humanize (explain searchset)))

(comment
  (require '[cljdoc.render.api-searchset :as api-searchset]
           '[cljdoc.spec.util :as util]
           '[malli.provider])

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

  ;; infer a schema to get you started
  (malli.provider/provide (mapv util/load-searchset version-entities))

  (def searchset (util/load-searchset (first version-entities)))

  (set (mapcat keys (:defs searchset)))
  (set (mapcat keys (:namespaces searchset)))
  (set (mapcat keys (:docs searchset)))

  (every? (comp valid? util/load-searchset)
          version-entities))

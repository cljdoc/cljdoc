(ns cljdoc.pathom
  (:require [com.wsscode.pathom.core :as p]
            [com.wsscode.pathom.connect :as pc]
            [clojure.java.jdbc :as sql]
            [clj-http.lite.client :as http]
            [cljdoc.config :as config]))

;; setup for a given connect subsystem
(defmulti resolver-fn pc/resolver-dispatch)
(def indexes (atom {}))
(def defresolver (pc/resolver-factory resolver-fn indexes))

(defresolver `dependencies-resolver
  {::pc/input  #{:cljdoc/artifact}
   ::pc/output [:cljdoc.artifact/dependencies [:cljdoc.artifact/group
                                               :cljdoc.artifact/name
                                               :cljdoc.artifact/version]]}
  (fn [env {:keys [cljdoc/artifact]}]
    (println artifact)
    {:cljdoc.artifact/dependencies [{:cljdoc.artifact/group "xxx"
                                     :cljdoc.artifact/name "xxx"
                                     :cljdoc.artifact/version "11.1.0"}]}))

(parser {} [{[:cljdoc/artifact 'test] [:cljdoc.artifact/dependencies]}]) ; PVector can't be cast to PMap
(parser {} [[:cljdoc/artifact 'test] [:cljdoc.artifact/dependencies]]) ; not found

(defresolver `person-resolver
  ;; The minimum data we must already know in order to resolve the outputs
  {::pc/input  #{:person/id}
   ;; A query template for what this resolver outputs
   ::pc/output [:person/name {:person/address [:address/id]}]}
  (fn [env {:keys [person/id] :as params}]
    ;; normally you'd pull the person from the db, and satisfy the listed
    ;; outputs. For demo, we just always return the same person details.
    {:person/name "Tom"
     :person/address {:address/id 1}}))

(defresolver `address-resolver
  {::pc/input  #{:address/id}
   ::pc/output [:address/city :address/state]}
  (fn [env {:keys [address/id] :as params}]
    {:address/city "Salem"
     :address/state "MA"}))

(def parser
  (p/parser {::p/plugins [(p/env-plugin
                            {::p/reader             [p/map-reader
                                                     pc/all-readers]
                             ::pc/resolver-dispatch resolver-fn
                             ::pc/indexes           @indexes})]}))

(comment
  (def env {:db (config/db (config/config))})

  (def a {:cljdoc.artifact/group "test"
          :cljdoc.artifact/name "testname"
          :cljdoc.artifact/version "0.1.0"})

  (parser {} [[:person/id 1] [:person/name {:person/address [:address/city]}]])
  (parser {} [[:cljdoc/artifact {:some 'test}] [:cljdoc.artifact/dependencies]])

  (parser {} [{[:cljdoc/artifact 'test] [:cljdoc.artifact/dependencies]}]) ; PVector can't be cast to PMap
  (parser {} [[:cljdoc/artifact 'test] [:cljdoc.artifact/dependencies]]) ; not found

  (parser {} [{[] [:cljdoc.artifact/dependencies]}])

  )

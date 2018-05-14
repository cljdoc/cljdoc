(ns cljdoc.routes
  (:require [bidi.bidi :as bidi]
            [bidi.verbose :refer [branch leaf param]]
            [cljdoc.spec]))

(defn html-routes [leaf-constructor]
  "This is the routing scheme for rendering static HTML files
  
  The `leaf-constructor` argument will be passed a keyword (route-id)
  and may return anyting that implements bidi.bidi/Matched. This separation
  has been put in place so the routing table remains plain data."
  (branch "" (param :group-id)
          (leaf "/" (leaf-constructor :group/index))
          (branch "/" (param :artifact-id)
                  (leaf "/" (leaf-constructor :artifact/index))
                  (branch "/" (param :version)
                          (leaf "/" (leaf-constructor :artifact/version))
                          (branch "/doc/" (param :doc-page)
                                  (leaf "/" (leaf-constructor :artifact/doc)))
                          (branch "/api/" (param [ #"[\w\.-]+" :namespace ])
                                  (leaf "" (leaf-constructor :artifact/namespace))
                                  (branch "#" (param :def)
                                          (leaf "" :artifact/def)))))))

(def transit-cache-routes
  "This is the routing scheme for rendering static HTML files"
  (branch "/" (param :group-id)
          (leaf "/" :transit/group-index)
          (branch "/" (param :artifact-id)
                  (leaf "/" :transit/artifact-index)
                  (branch "/" (param :version)
                          (leaf ".transit" :transit/version)))))

(def routes
  ;; TODO implement grimoire URIs
  (branch ""
          transit-cache-routes
          (html-routes identity)))

(defn path-for [k params]
  ;; NOTE spec/conform could be used to eliminate key param
  ;; NOTE multiple routing trees could be implemented with only
  ;; a style argument being passed: :html, :grimoire, :spa, :api
  (cljdoc.spec/assert :cljdoc.spec/grimoire-entity params)
  (apply bidi/path-for routes k (apply concat params))) ;; *shakes head*

(comment
  (->> {:group-id "group" :artifact-id "artifact" :version "version" :namespace "some.ns" :def "def"}
       (path-for :artifact/def)
       #_(bidi/match-route routes)) ; TODO figoure out why route matching does not work

  (->> {:group-id "yada", :artifact-id "yada", :version "1.2.10", :doc-page "preface"}
       (path-for :artifact/doc)
       #_(bidi/match-route html-routes)) ; TODO figoure out why route matching does not work

  (bidi/match-route routes p)

  (bidi/match-route routes "/org.clojure/clojure/1.1/doc/asd/")
  (bidi/match-route routes "/org.clojure/clojure/1.1/api/some.namespace/")

  (bidi/match-route routes "/org.clojure/clojure/1.1/some.namespace/#def")

  (bidi/match-route routes "/org.clojure/clojure/1.1/some.ns/")

  (bidi/match-route routes "/org.clojure/")

  (bidi/match-route routes "/org.clojure/clojure/")

  (bidi/match-route routes "/oeclojure/clojure/1/")

  )

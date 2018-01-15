;; Some of this is copied from
;; github.com/clojure-grimoire/lein-grim/blob/master/src/grimoire/doc.clj
(ns cljdoc.grimoire-helpers
  (:require [clojure.spec.alpha :as spec]
            [clojure.java.classpath :as cp]
            [clojure.tools.namespace.find :as tns.f]
            [clojure.repl]
            [codox.main]
            [grimoire.api]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.things]
            [grimoire.util]
            [grimoire.either]
            [detritus.var]))

(def v "0.1.0")

(defn var->type
  "Function from a var to the type of the var.
  - Vars tagged as dynamic or satisfying the .isDynamic predicate are tagged
    as :var values.
  - Vars tagged as macros (as required by the macro contract) are tagged
    as :macro values.
  - Vars with fn? or MultiFn values are tagged as :fn values.
  - All other vars are simply tagged as :var."
  [v]
  {:pre [(var? v)]}
  (let [m (meta v)]
    (cond (:macro m)                            :macro
          (or (:dynamic m)
              (.isDynamic ^clojure.lang.Var v)) :var
          (or (fn? @v)
              (instance? clojure.lang.MultiFn @v)) :fn
          :else :var)))

(defn ns-stringifier
  "Function something (either a Namespace instance, a string or a symbol) to a
  string naming the input. Intended for use in computing the logical \"name\" of
  the :ns key which could have any of these values."
  [x]
  (cond (instance? clojure.lang.Namespace x) (name (ns-name x))
        (symbol? x)   (name x)
        (string? x)   x
        :else         (throw (Exception. (str "Don't know how to stringify " x)))))

(defn name-stringifier
  "Function from something (either a symbol, string or something else) which if
  possible computes the logical \"name\" of the input as via clojure.core/name
  otherwise throws an explicit exception."
  [x]
  (cond (symbol? x) (name x)
        (string? x) x
        :else       (throw (Exception. (str "Don't know how to stringify " x)))))

(defn write-docs-for-var
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [store def-thing var]
  ;; TODO assert def-thing is def-thing
  (let [docs (-> (meta var)
                 (assoc  ;; TODO there are issues with source-fn, for details see:
                         ;; https://github.com/martinklepsch/clj-docs#grimoire
                         ;; :src  (var->src var)
                         :src  (clojure.repl/source-fn (symbol (subs (str var) 2)))
                         :type (var->type var))
                 ;; @arrdem meta takes precedence here — are there situations where name and
                 ;; ns would differe from whatever is encoded in the grimoire/thing?
                 (update :name #(name-stringifier (or %1 (grimoire.things/thing->name var))))
                 (update :ns   #(ns-stringifier (or %1 (grimoire.things/thing->name (grimoire.things/thing->namespace var)))))
                 (dissoc :inline
                         :protocol
                         :inline
                         :inline-arities))]
    (assert (:name docs) "Var name was nil!")
    (assert (:ns docs) "Var namespace was nil!")
    (grimoire.api/write-meta store def-thing docs)))

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which writes namespace metadata
  to the :datastore in config."
  [store ns-thing ns]
  ;; TODO assert ns-thing is ns-thing
  (let [ns-meta (-> ns the-ns meta (or {}))]
    (grimoire.api/write-meta store ns-thing ns-meta)
    (println "Finished" ns)))

(defn build-grim [platf-entity src dst]
  (spec/assert :cljdoc.spec/platform-entity platf-entity)
  (assert dst "target dir missing!")
  (assert src "source dir missing!")
  (let [store    (grimoire.api.fs/->Config dst "" "")
        platform (-> (grimoire.things/->Group    (:group-id platf-entity))
                     (grimoire.things/->Artifact (:artifact-id platf-entity))
                     (grimoire.things/->Version  (:version platf-entity))
                     (grimoire.things/->Platform (grimoire.util/normalize-platform (:platform platf-entity))))]

    ;; Write metadata for the group, artifact, version & platform
    ;; OLD VERSION
    ;; (reduce (fn [acc f]
    ;;           (grimoire.api/write-meta (:datastore config) acc {})
    ;;           (f acc))
    ;;         (grimoire.things/->Group groupid)
    ;;         [#(grimoire.things/->Artifact % artifactid)
    ;;          #(grimoire.things/->Version % version)
    ;;          #(grimoire.things/->Platform % platform)
    ;;          identity])
    ;; ----------------------------------------
    (println "Writing bare meta for"
             (grimoire.things/thing->path (grimoire.things/thing->version platform)))
    (grimoire.api/write-meta store (grimoire.things/thing->group platform) {})
    (grimoire.api/write-meta store (grimoire.things/thing->artifact platform) {})
    (grimoire.api/write-meta store (grimoire.things/thing->version platform) {})
    (grimoire.api/write-meta store platform {})

    (let [namespaces (#'codox.main/read-namespaces
                      {:language     :clojure
                       ;; not sure what :root-path is needed for
                       :root-path    (System/getProperty "user.dir")
                       :source-paths [src]
                       :namespaces   :all
                       :metadata     {}
                       :exclude-vars #"^(map)?->\p{Upper}"})]
      (doseq [ns namespaces
              :let [publics  (:publics ns)
                    ns-thing (grimoire.things/->Ns platform (-> ns :name name))]]
        (write-docs-for-ns store ns-thing (:name ns))
        (doseq [public     publics
                :let [def-thing (grimoire.things/->Def ns-thing (-> public :name name))]]
          (write-docs-for-var
           store
           def-thing
           (resolve (symbol (-> ns :name name)
                            (-> public :name name)))))))))

(comment
  (build-grim {:group-id "bidi"
               :artifact-id  "bidi"
               :version "2.1.3"
               :platform "clj"}
              "target/jar-contents/" "target/grim-test")

  (->> (#'codox.main/read-namespaces
        {:language     :clojure
         ;; :root-path    (System/getProperty "user.dir")
         :source-paths ["target/jar-contents/"]
         :namespaces   :all
         :metadata     {}
         :exclude-vars #"^(map)?->\p{Upper}"}))

  (let [c (grimoire.api.fs/->Config "target/grim-test" "" "")]
    (build-grim "bidi" "bidi" "2.1.3" "target/jar-contents/" "target/grim-test")
    #_(write-docs-for-var c (var bidi.bidi/match-route)))

  (resolve (symbol "bidi.bidi" "match-route"))

  (var->src (var bidi.bidi/match-route))
  ;; (symbol (subs (str (var bidi.bidi/match-route)) 2))
  ;; (clojure.repl/source-fn 'bidi.bidi/match-route)
  ;; (var (symbol "bidi.bidi/match-route"))

  )

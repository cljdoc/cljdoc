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

(defn sanitize-cdx [cdx-namespace]
  ;; :publics contain a field path which is a
  ;; file, this cannot be serialized accross
  ;; pod boundaries by default
  (let [clean-public #(update % :path (fn [file] (.getPath file)))
        remove-unneeded #(dissoc % :path :members)  ; TODO what are members?
        stringify-name #(update % :name name)]
    (-> cdx-namespace
        (update :name name)
        (update :publics #(map (comp stringify-name remove-unneeded) %)))))

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

(defn write-docs-for-def
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [store def-thing codox-meta]
  (assert (grimoire.things/def? def-thing))
  (let [docs (-> codox-meta
                 (update :name name))]
    (when-not (:name docs)
      (println "Var name missing:" docs))
    (assert (:name docs) "Var name was nil!")
    (assert (nil? (:namespace docs)) "Namespace should not get written to def-meta")
    (assert (nil? (:platform docs)) "Platform should not get written to def-meta")
    (spec/assert :cljdoc.spec/def-minimal docs)
    (grimoire.api/write-meta store def-thing docs)))

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which writes namespace metadata
  to the :datastore in config."
  [store ns-thing ns-meta]
  (assert (grimoire.things/namespace? ns-thing))
  (grimoire.api/write-meta store ns-thing ns-meta)
  (println "Finished namespace" (:name ns-meta)))

(defn build-grim [platf-entity codox-namespaces dst]
  (spec/assert :cljdoc.spec/platform-entity platf-entity)
  (assert dst "target dir missing!")
  (assert (coll? codox-namespaces) "codox-namespaces malformed")
  (let [store    (grimoire.api.fs/->Config dst "" "")
        platform (-> (grimoire.things/->Group    (:group-id platf-entity))
                     (grimoire.things/->Artifact (:artifact-id platf-entity))
                     (grimoire.things/->Version  (:version platf-entity))
                     (grimoire.things/->Platform (grimoire.util/normalize-platform (:platform platf-entity))))]

    (println "Writing bare meta for"
             (grimoire.things/thing->path (grimoire.things/thing->version platform)))
    (grimoire.api/write-meta store (grimoire.things/thing->group platform) {})
    (grimoire.api/write-meta store (grimoire.things/thing->artifact platform) {})
    (grimoire.api/write-meta store (grimoire.things/thing->version platform) {})
    (grimoire.api/write-meta store platform {})

    (let [namespaces codox-namespaces]
      (doseq [ns namespaces
              :let [publics  (:publics ns)
                    ns-thing (grimoire.things/->Ns platform (-> ns :name name))]]
        (write-docs-for-ns store ns-thing (dissoc ns :publics))
        (doseq [public publics]
          (try
            (write-docs-for-def store
                                (grimoire.things/->Def ns-thing (-> public :name name))
                                public)
            (catch Throwable e
              (throw (ex-info "Failed to write docs for def"
                              {:codox/public public}
                              e)))))))))

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

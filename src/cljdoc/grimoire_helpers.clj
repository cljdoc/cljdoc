(ns cljdoc.grimoire-helpers
  (:require [clojure.spec.alpha :as spec]
            [grimoire.api]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.things]
            [grimoire.util]
            [grimoire.either]))

(def v "0.1.0")

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

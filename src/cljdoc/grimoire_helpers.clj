(ns cljdoc.grimoire-helpers
  (:refer-clojure :exclude [import])
  (:require [clojure.tools.logging :as log]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.config :as cfg]
            [cljdoc.spec]
            [cljdoc.util]
            [grimoire.api]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.things]
            [grimoire.util]
            [grimoire.either])
  (:import [org.eclipse.jgit.api Git]))

(def v "0.1.0")

(defn grimoire-write
  "Like `grimore.api/write-meta` but assert that no :name field is
  written as part of the metadata since that would duplicate
  information already encoded in the thing-hierarchy."
  [store thing meta]
  (assert (nil? (:name meta)) (format "Name not nil: %s" meta))
  (cond (grimoire.things/def? thing)
        (cljdoc.spec/assert :cljdoc.grimoire/def meta)
        (grimoire.things/namespace? thing)
        (cljdoc.spec/assert :cljdoc.grimoire/namespace meta))
  (grimoire.api/write-meta store thing meta))

(defn write-docs-for-def
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [store def-thing codox-public]
  (assert (grimoire.things/def? def-thing))
  (cljdoc.spec/assert :cljdoc.codox/public codox-public)
  (assert (= (-> codox-public :name name) (grimoire.things/thing->name def-thing))
          (format "meta <> grimoire thing missmatch: %s <> %s" (:name codox-public)
                  (grimoire.things/thing->name def-thing)))
  (assert (nil? (:namespace codox-public)) "Namespace should not get written to def-meta")
  (assert (nil? (:platform codox-public)) "Platform should not get written to def-meta")
  (grimoire-write store def-thing (dissoc codox-public :name)))

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which writes namespace metadata
  to the :datastore in config."
  [store ns-thing ns-meta]
  (assert (grimoire.things/namespace? ns-thing))
  (cljdoc.spec/assert :cljdoc.grimoire/namespace ns-meta)
  (grimoire-write store ns-thing ns-meta))

(defn thing
  ([group]
   (grimoire.things/->Group group))
  ([group artifact]
   (grimoire.things/->Artifact (thing group) artifact))
  ([group artifact version]
   (grimoire.things/->Version (thing group artifact) version))
  ([group artifact version platf]
   (grimoire.things/->Platform (thing group artifact version)
                               (grimoire.util/normalize-platform platf))))

(defn version-thing
  ([project version]
   (thing (cljdoc.util/group-id project) (cljdoc.util/artifact-id project) version))
  ([group artifact version]
   (thing group artifact version)))

(defn platform-thing [project version platf]
  (thing (cljdoc.util/group-id project) (cljdoc.util/artifact-id project) version platf))

(defn exists? [store t]
  (grimoire.either/succeed? (grimoire.api/read-meta store t)))

(defn grimoire-store [^java.io.File dir]
  (grimoire.api.fs/->Config (.getPath dir) "" ""))

(defn import-api* [{:keys [platform store codox-namespaces]}]
  (grimoire.api/write-meta store platform {})
  (let [namespaces codox-namespaces]
    (doseq [ns namespaces
            :let [publics  (:publics ns)
                  ns-thing (grimoire.things/->Ns platform (-> ns :name name))]]
      (write-docs-for-ns store ns-thing (dissoc ns :publics :name))
      (doseq [public publics]
        (try
          (write-docs-for-def store
                              (grimoire.things/->Def ns-thing (-> public :name name))
                              public)
          (catch Throwable e
            (throw (ex-info "Failed to write docs for def"
                            {:codox/public public}
                            e)))))
      (log/info "Finished namespace" (:name ns)))))

(defn write-bare
  [store version]
  {:pre [(grimoire.things/version? version)]}
  (doto store
    (grimoire.api/write-meta (grimoire.things/thing->group version) {})
    (grimoire.api/write-meta (grimoire.things/thing->artifact version) {})
    (grimoire.api/write-meta version {})))

(defn import-doc
  [{:keys [version store doc-tree scm jar]}]
  {:pre [(grimoire.things/version? version)
         (some? store)
         (some? scm)
         (some? doc-tree)]}
  (log/info "Writing doc meta for" (grimoire.things/thing->path version) {:scm (dissoc scm :files)})
  (grimoire.api/write-meta store version {:jar jar :scm scm, :doc doc-tree}))

(defn import-api
  [{:keys [version codox store]}]
  ;; TODO assert format of cljdoc-edn
  (doseq [platf (keys codox)]
    (assert (#{"clj" "cljs"} platf) (format "was %s" platf))
    (import-api* {:platform (grimoire.things/->Platform version platf)
                  :store store
                  :codox-namespaces (get codox platf)})))

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

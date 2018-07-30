(ns cljdoc.storage.grimoire-impl
  "Grimoire is great but reading from many different places of the filesystem
  can make the code very platform dependent and harder to adapt to new contexts.

  This namespace is intended at converting data from a grimoire store into a
  developer-friendly bundle for each group/artifact/version pairing.

  A plain data file that contains all required information is an abstraction on
  top of Grimoire that will make it easier for other developers to get started
  utilizing the data stored in the Grimoire store.

  In theory the cache should contain all required information to
  restore the information into a Grimoire store although this is not
  an explicit goal.

  The cache is structured as simple as possible (a list of maps) in order
  to ease understand, avoid complexity and allow people to get started quickly.
  This may result in some duplication which I hope to mitigate by encoding the
  cache in the transit format: https://github.com/cognitect/transit-format.

  The format of the cache is defined in the spec :cljcdoc.spec/cache-bundle"
  (:require [cljdoc.spec]
            [clojure.java.io :as io]
            [clojure.tools.logging :as log]
            [grimoire.api.fs]
            [grimoire.api.fs.write]
            [grimoire.api.fs.read]
            [grimoire.api.fs.impl]
            [grimoire.api :as grim]
            [grimoire.things :as things]
            [grimoire.either :as e]))

;; NOTE maybe cache contents should be versioned to some degree
;; so people can figure out which format they should expect?
;; This could become especially useful in a context where a tool
;; may work with older caches that have been generated with
;; a different cache-contents layout.

(defn- docs-cache-contents [store version-t]
  (let [platf-things (e/result (grim/list-platforms store version-t))
        platforms    (for [platform  platf-things]
                       (e/result (grim/read-meta store platform)))
        namespaces   (for [platform  platf-things
                           namespace (e/result (grim/list-namespaces store platform))]
                       (assoc (e/result (grim/read-meta store namespace))
                              :name (things/thing->name namespace)
                              :platform (things/thing->name platform)))
        defs         (for [platform  platf-things
                           namespace (e/result (grim/list-namespaces store platform))
                           def       (e/result (grim/list-defs store namespace))]
                       (assoc (e/result (grim/read-meta store def))
                              :name (things/thing->name def)
                              :platform (things/thing->name platform)
                              :namespace (things/thing->name namespace)))]
    {:version   (e/result (grim/read-meta store version-t))
     :group     (e/result (grim/read-meta store (things/thing->group version-t)))
     :artifact  (e/result (grim/read-meta store (things/thing->artifact version-t)))
     :platforms  platforms
     :namespaces namespaces
     :defs       defs}))

(defn bundle-docs
  [store version-t]
  (assert (things/version? version-t) "bundle-docs expects a grimoire.things/version argument")
  (assert (e/succeed? (grim/read-meta store version-t))
          (format "version meta is missing for version %s" (things/thing->name version-t)))
  (log/info "Bundling docs" (things/thing->url-path version-t))
  (->> {:cache-contents (docs-cache-contents store version-t)
        :cache-id       {:group-id    (-> version-t things/thing->group things/thing->name)
                         :artifact-id (-> version-t things/thing->artifact things/thing->name)
                         :version     (-> version-t things/thing->name)}}
       (cljdoc.spec/assert :cljdoc.spec/cache-bundle)))

(defn bundle-group
  [store group-t]
  (assert (things/group? group-t) "bundle-group expects a grimoire.things/group argument")
  (log/info "Bundling group info" (things/thing->url-path group-t))
  (let [artifacts (e/result (grim/list-artifacts store group-t))]
    (->> {:cache-contents {:versions  (for [a artifacts
                                            v (e/result (grim/list-versions store a))]
                                        {:artifact-id (things/thing->name a)
                                         :version     (things/thing->name v)})
                           :artifacts (map :name artifacts)}
          :cache-id       {:group-id  (-> group-t things/thing->name)}}
         (cljdoc.spec/assert :cljdoc.spec/cache-bundle))))

(defn stats
  [store]
  (let [versions (for [g (e/result (grim/list-groups store))
                       a (e/result (grim/list-artifacts store g))
                       v (e/result (grim/list-versions store a))]
                   v)]
    {:versions (count versions)
     :artifacts (count (set (map things/thing->artifact versions)))}))

(defn all-versions
  [store]
  (for [g (e/result (grim/list-groups store))
        a (e/result (grim/list-artifacts store g))
        v (e/result (grim/list-versions store a))]
    {:group-id (things/thing->name g)
     :artifact-id (things/thing->name a)
     :version (things/thing->name v)}))

;; Writing -----------------------------------------------------------

(defn grimoire-write
  "Like `grimore.api/write-meta` but assert that no :name field is
  written as part of the metadata since that would duplicate
  information already encoded in the thing-hierarchy."
  [store thing meta]
  (assert (nil? (:name meta)) (format "Name not nil: %s" meta))
  (cond (things/def? thing)
        (cljdoc.spec/assert :cljdoc.grimoire/def meta)
        (things/namespace? thing)
        (cljdoc.spec/assert :cljdoc.grimoire/namespace meta))
  (grim/write-meta store thing meta))

(defn write-docs-for-def
  "General case of writing documentation for a Var instance with
  metadata. Compute a \"docs\" structure from the var's metadata and then punt
  off to write-meta which does the heavy lifting."
  [store def-thing codox-public]
  (assert (things/def? def-thing))
  (cljdoc.spec/assert :cljdoc.codox/public codox-public)
  (assert (= (-> codox-public :name name) (things/thing->name def-thing))
          (format "meta <> grimoire thing missmatch: %s <> %s" (:name codox-public)
                  (things/thing->name def-thing)))
  (assert (nil? (:namespace codox-public)) "Namespace should not get written to def-meta")
  (assert (nil? (:platform codox-public)) "Platform should not get written to def-meta")
  (grimoire-write store def-thing (dissoc codox-public :name)))

(defn write-docs-for-ns
  "Function of a configuration and a Namespace which writes namespace metadata
  to the :datastore in config."
  [store ns-thing ns-meta]
  (assert (things/namespace? ns-thing))
  (cljdoc.spec/assert :cljdoc.grimoire/namespace ns-meta)
  (grimoire-write store ns-thing ns-meta))

(defn thing
  ([group]
   (things/->Group group))
  ([group artifact]
   (things/->Artifact (thing group) artifact))
  ([group artifact version]
   (things/->Version (thing group artifact) version))
  ([group artifact version platf]
   (things/->Platform (thing group artifact version)
                               (grimoire.util/normalize-platform platf))))

(defn version-thing
  ([project version]
   (thing (cljdoc.util/group-id project) (cljdoc.util/artifact-id project) version))
  ([group artifact version]
   (thing group artifact version)))

(defn platform-thing [project version platf]
  (thing (cljdoc.util/group-id project) (cljdoc.util/artifact-id project) version platf))

(defn exists? [store t]
  (e/succeed? (grim/read-meta store t)))

(defn grimoire-store [^java.io.File dir]
  (grimoire.api.fs/->Config (.getPath dir) "" ""))

(defn import-api* [{:keys [platform store codox-namespaces]}]
  (grim/write-meta store platform {})
  (let [namespaces codox-namespaces]
    (doseq [ns namespaces
            :let [publics  (:publics ns)
                  ns-thing (things/->Ns platform (-> ns :name name))]]
      (write-docs-for-ns store ns-thing (dissoc ns :publics :name))
      (doseq [public publics]
        (try
          (write-docs-for-def store
                              (things/->Def ns-thing (-> public :name name))
                              public)
          (catch Throwable e
            (throw (ex-info "Failed to write docs for def"
                            {:codox/public public}
                            e)))))
      (log/info "Finished namespace" (:name ns)))))

(defn write-bare
  [store version]
  {:pre [(things/version? version)]}
  (doto store
    (grim/write-meta (things/thing->group version) {})
    (grim/write-meta (things/thing->artifact version) {})
    (grim/write-meta version {})))

(defn import-doc
  [{:keys [version store doc-tree scm jar]}]
  {:pre [(things/version? version)
         (some? store)
         (some? scm)
         (some? doc-tree)]}
  (log/info "Writing doc meta for" (things/thing->path version) {:scm (dissoc scm :files)})
  (grim/write-meta store version {:jar jar :scm scm, :doc doc-tree}))

(defn- delete-thing! [store thing]
  (let [thing-dir (.getParentFile (grimoire.api.fs.impl/thing->meta-handle store thing))]
    (when (.exists thing-dir)
      (log/info "Deleting all previously imported data for" (things/thing->path thing))
      (cljdoc.util/delete-directory! thing-dir))))

(defn import-api
  [{:keys [version codox store]}]
  ;; TODO assert format of cljdoc-edn
  (doseq [platf (keys codox)]
    (assert (#{"clj" "cljs"} platf) (format "was %s" platf))
    (delete-thing! store (things/->Platform version platf))
    (import-api* {:platform (things/->Platform version platf)
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

(comment
  (do
    (def store (grimoire.api.fs/->Config "data/grimoire" "" ""))
    (def pp clojure.pprint/pprint)

    (def gt (things/->Group "instaparse"))
    (def at (things/->Artifact gt "instaparse"))
    (def vt (things/->Version at "1.4.9"))
    (def pt (things/->Platform vt "clj"))
    (def cache (bundle-docs store vt))
    (defn dev-cache [] (bundle-docs store vt)))

  (bundle-group store gt)

  (count (stats store))

  (map :namespace (namespaces cache))

  (def x (grimoire.api/write-meta store vt {:test "s"}))

  clojure.spec.alpha/*compile-asserts*

  (clojure.spec.alpha/check-asserts true)

  (cljdoc.spec/assert string? 1)


  (def cache (bundle-docs store vt))

  (clojure.pprint/pprint (dev-cache))

  (-> (:cache-contents (dev-cache))
      (update :defs #(take 1 %))
      (update :namespaces #(take 2 %))
      pp)

  (clojure.spec.alpha/check-asserts true)

  )

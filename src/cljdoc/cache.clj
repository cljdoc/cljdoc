(ns cljdoc.cache
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
            [clojure.spec.alpha :as spec]
            [grimoire.api.fs]
            [grimoire.api.fs.read]
            [grimoire.api :as grim]
            [grimoire.things :as things]
            [grimoire.either :as e]))

(defn- cache-contents [store version-t]
  (for [platform  (e/result (grim/list-platforms store version-t))
        namespace (e/result (grim/list-namespaces store platform))
        def       (e/result (grim/list-defs store namespace))]
    (let [def-meta (e/result (grim/read-meta store def))]
      (println "Bundling" (str (:name namespace) "/" (:name def)))
      (assoc def-meta
             :platform (things/thing->name platform)
             :namespace (:name namespace)))))

(defn bundle-docs
  [store version-t]
  (assert (things/version? version-t) "bundle-docs expects a grimoire.things/version argument")
  (->> {:cache-contents (cache-contents store version-t)
        :cache-id       {:group-id (-> version-t things/thing->group things/thing->name)
                         :artifact-id (-> version-t things/thing->artifact things/thing->name)
                         :version (-> version-t things/thing->name)
                         :scm-url "" ;; TODO needs to go into grimoire meta
                         }}
       (spec/assert :cljdoc.spec/cache-bundle)))

(defprotocol ICacheRenderer
  "This protocol is intended to allow different implementations of renderers.

  Intended implementations:
  - Single Transit file
  - Multiple HTML files
  - Single HTML file
  - Single Page Apps
    - Single HTML file (CLJS app only working with existing data)
    - Global app (CLJS app with ways out of the current context: other projects, versions etc.)
    - Both of them could share a lot of code but have slightly different entry points"
  (render [this cache output-config]
    "Render contents of cache to :file or :dir specified in output-config"))

(comment
  (def store (grimoire.api.fs/->Config "target/grimoire" "" ""))

  (def vt (-> (things/->Group "bidi")
              (things/->Artifact "bidi")
              (things/->Version "2.1.3")))

  spec/*compile-asserts*

  (spec/check-asserts true)

  (spec/assert string? 1)


  (def cache (bundle-docs store vt))

  (clojure.pprint/pprint (:cache-id c))


  )

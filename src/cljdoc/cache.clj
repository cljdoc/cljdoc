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
  ;;
  ;; TODO currently has no support for metadata on namespace or really
  ;; anything beyond defs. This also plays into the question where to
  ;; put scm-url. Maybe something like this could work:
  ;;
  ;;     {:cache-id 'as-is
  ;;      :cache-contents {:version    'version-meta
  ;;                       :artifact   'artifact-meta
  ;;                       :group      'group-meta
  ;;                       :platforms  ['platform-meta]
  ;;                       :namespaces ['namespace-meta]
  ;;                       :defs       ['def-meta]}}
  ;;
  ;; Generate this from a grimoire.thing input. For all sub-nodes that
  ;; contain multiple children inline the information about their
  ;; respective parent e.g. at platform and namespace keys to def-meta
  ;;
  ;; Probably we don't want to duplicate any data between
  ;; cache-id and the group/artifact/version meta stuff.
  ;;
  ;; IMPORTANT Even if we don't go ahead with something like the above
  ;; it is very important for future extension to turn `cache-contents`
  ;; into a map!
  ;;
  (:require [cljdoc.spec]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [grimoire.api.fs]
            [grimoire.api.fs.read]
            [grimoire.api :as grim]
            [grimoire.things :as things]
            [grimoire.either :as e]))

;; NOTE maybe cache contents should be versioned to some degree
;; so people can figure out which format they should expect?
;; This could become especially useful in a context where a tool
;; may work with older caches that have been generated with
;; a different cache-contents layout.

(defn- cache-contents [store version-t]
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
  (->> {:cache-contents (cache-contents store version-t)
        :cache-id       {:group-id    (-> version-t things/thing->group things/thing->name)
                         :artifact-id (-> version-t things/thing->artifact things/thing->name)
                         :version     (-> version-t things/thing->name)}}
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
  (do
    (def store (grimoire.api.fs/->Config "target/grimoire" "" ""))
    (def pp clojure.pprint/pprint)

    (def vt (-> (things/->Group "bidi")
                (things/->Artifact "bidi")
                (things/->Version "2.1.3")))
    (def pt (-> vt (things/->Platform "clj")))
    (def cache (bundle-docs store vt))
    (defn dev-cache [] (bundle-docs store vt)))

  (e/result (grim/list-namespaces store pt))

  (def x (grimoire.api/write-meta store vt {:test "s"}))
  ()

  spec/*compile-asserts*

  (spec/check-asserts true)

  (spec/assert string? 1)


  (def cache (bundle-docs store vt))

  (clojure.pprint/pprint (dev-cache))

  (-> (:cache-contents (dev-cache))
      (update :defs #(take 1 %))
      (update :namespaces #(take 2 %))
      pp)

  (spec/check-asserts true)

  )

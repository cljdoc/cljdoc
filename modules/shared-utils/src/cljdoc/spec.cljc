(ns cljdoc.spec
  (:refer-clojure :exclude [assert])
  (:require [clojure.spec.alpha :as s]))

;; Basic list of struff that can be found in grimoire ----------------

(s/def ::name string?)
(s/def ::doc (s/nilable string?))
(s/def ::src string?)
(s/def ::type #{:var :fn :macro :protocol :multimethod})
(s/def ::line (s/and int? pos?))
(s/def ::column (s/and int? pos?))

(s/def ::def-minimal
  (s/keys :req-un [::name ::type]
          ;; figure out why type is missing sometimes
          ;; also Codox currently does not support :src
          ;; and with :language :clojurescript it also
          ;; does not support :column
          :opt-un [::doc
                   ::src
                   ::line])) ; may not be present in vars that have been defined dynamically

(s/def ::platform #{"clj" "cljs"})
(s/def ::namespace string?)

(s/def ::def-full
  (s/merge ::def-minimal
           (s/keys :req-un [::platform ::namespace])))

;; Entity maps -------------------------------------------------------
;; These are basically intended to serve the same purpose as
;; grimoire.things but in a plain data, cross platform fashion

(s/def ::group-id string?)
(s/def ::artifact-id string?)
(s/def ::version string?)
(s/def ::git-tag string?)
(s/def ::git-sha string?)
(s/def ::scm-url string?)

(s/def ::group-entity
  (s/keys :req-un [::group-id]))

(s/def ::artifact-entity
  (s/merge ::group-entity (s/keys :req-un [::artifact-id])))

(s/def ::version-entity
  (s/merge ::artifact-entity (s/keys :req-un [::version])))

(s/def ::platform-entity
  (s/merge ::version-entity (s/keys :req-un [::platform])))

(s/def ::namespace-entity
  (s/merge ::version-entity (s/keys :req-un [::namespace])))

(s/def ::def string?)
(s/def ::def-entity
  (s/merge ::namespace-entity (s/keys :req-un [::def])))

(s/def ::grimoire-entity
  ;; Ordering matters for conforming
  (s/or :def        ::def-entity
        :namespace  ::namespace-entity
        :platform   ::platform-entity
        :version    ::version-entity
        :artifact   ::artifact-entity
        :group      ::group-entity))


;; Cache specific ----------------------------------------------------

;; Docs-cache: this is intended for a specific
;; versioned artifact, e.g. [re-frame 0.10.5]
(s/def ::defs (s/coll-of ::def-full :gen-max 2))
(s/def ::namespaces (s/coll-of map? :gen-max 2))
(s/def ::latest ::version)

(s/def ::docs-cache
  (s/keys :req-un [::defs ::namespaces ::latest]))

(s/def :cache/artifacts (s/coll-of string?))
(s/def :cache/versions (s/coll-of (s/keys :req-un [::version ::artifact-id])))
(s/def ::group-cache
  (s/keys :req-un [:cache/versions :cache/artifacts]))

;; Cache bundle (combination of the above cache specs)

(s/def ::cache-contents
  (s/or :docs  ::docs-cache
        :group ::group-cache))

(s/def ::cache-id ::grimoire-entity)

(s/def ::cache-bundle
  ;; Not using 'id' and 'contents' as keys here because
  ;; this map is intended as part of the API and explicitly
  ;; mentioning that this data is related to the cache may
  ;; help them to understand the API faster
  (s/keys :req-un [::cache-id ::cache-contents]))


;; codox -------------------------------------------------------------
;; A spec for Codox namespace analysis data

(s/def :cljdoc.codox.public/name symbol? #_(s/or :a string? :b symbol?))
(s/def :cljdoc.codox.public/file string?)
(s/def :cljdoc.codox.public/line int?)
(s/def :cljdoc.codox.public/arglists coll?)
(s/def :cljdoc.codox.public/doc (s/nilable string?))
(s/def :cljdoc.codox.public/type #{:var :fn :macro :protocol :multimethod})
(s/def :cljdoc.codox.public/members (s/coll-of :cljdoc.codox/public))

(s/def :cljdoc.codox/public
  (s/keys :req-un [:cljdoc.codox.public/name
                   :cljdoc.codox.public/type]
          :opt-un [:cljdoc.codox.public/deprecated
                   :cljdoc.codox.public/doc
                   :cljdoc.codox.public/arglists
                   :cljdoc.codox.public/file
                   :cljdoc.codox.public/line
                   :cljdoc.codox.public/members]))

(s/def :cljdoc.codox.namespace/name symbol?)
(s/def :cljdoc.codox.namespace/publics (s/coll-of :cljdoc.codox/public))
(s/def :cljdoc.codox.namespace/doc (s/nilable string?))

(s/def :cljdoc.codox/namespace
  (s/keys :req-un [:cljdoc.codox.namespace/name
                   :cljdoc.codox.namespace/publics]
          :opt-un [:cljdoc.codox.namespace/doc]))


;; cljdoc.edn ---------------------------------------------------------

(s/def :cljdoc.cljdoc-edn/codox
  (s/map-of ::platform (s/coll-of :cljdoc.codox/namespace)))

(s/def :cljdoc.cljdoc-edn/pom-str string?)

(s/def :cljdoc/cljdoc-edn
  (s/keys :req-un [:cljdoc.cljdoc-edn/codox
                   :cljdoc.cljdoc-edn/pom-str]))


;; grimoire -----------------------------------------------------------

(s/def :cljdoc.grimoire/def
  ;; like codox output but without name
  (s/keys :req-un [:cljdoc.codox.public/type]
          :opt-un [:cljdoc.codox.public/deprecated
                   :cljdoc.codox.public/doc
                   :cljdoc.codox.public/arglists
                   :cljdoc.codox.public/file
                   :cljdoc.codox.public/line
                   :cljdoc.codox.public/members]))

(s/def :cljdoc.grimoire/namespace
  (s/keys :opt-un [:cljdoc.codox.namespace/doc]))

;; search ----------------------------------------------------------

(s/def :artifact/description string?)
(s/def :artifact/origin #{:clojars :maven-central})
(s/def :artifact/versions (s/coll-of ::version))
(s/def ::artifact (s/keys
                    :req-un [::artifact-id ::group-id]
                    :opt-un [:artifact/description
                             :artifact/versions
                             :artifact/origin]))

;; utilities ----------------------------------------------------------

(defn assert [spec v]
  (if (s/get-spec spec)
    (s/assert spec v)
    (throw (Exception. (format "No spec found for %s" spec)))))

(comment
  (require '[clojure.spec.gen.alpha :as gen])

  (gen/sample (s/gen ::def))
  (gen/sample (s/gen ::def-full))

  (clojure.pprint/pprint
   (s/conform :cljdoc.spec/cache-bundle
              (first (gen/sample (s/gen ::cache-bundle)))))

  (s/conform :cljdoc.spec/grimoire-entity
             (first (gen/sample (s/gen ::grimoire-entity))))

  (clojure.pprint/pprint
   (first (gen/sample (s/gen ::versions-cache))))

  (gen/sample (s/gen ::namespace))
  (gen/sample (s/gen :cache/artifact))


  (def x
    {:name "bidi.bidi"
     :defs [{:line 404,
             :column 1,
             :file "bidi/bidi.cljc",
             :name "Matches",
             :ns "bidi.bidi",
             :doc nil,
             :src "(defprotocol Matches\n  (matches [_] \"A protocol used in the expansion of possible matches that the pattern can match. This is used to gather all possible routes using route-seq below.\"))",
             :type :var}]})

  (s/valid? ::namespace x))



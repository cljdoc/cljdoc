(ns cljdoc.spec
  (:require [clojure.spec.alpha :as s]))

;; Basic list of struff that can be found in grimoire ----------------

(s/def ::name string?)
(s/def ::doc (s/nilable string?))
(s/def ::src string?)
(s/def ::type #{:var :fn :macro})
(s/def ::line (s/and int? pos?))
(s/def ::column (s/and int? pos?))

(s/def ::def-minimal
  (s/keys :req-un [::name ::src ::line ::column]
          :opt-un [::doc ::type])) ;; figure out why these are missing simetimes

(s/def ::platform #{"clj" "cljs"})
(s/def ::namespace string?)

(s/def ::def-full
  (s/merge ::def-minimal
           (s/keys :req-un [::platform ::namespace])))

(s/def ::group-id string?)
(s/def ::artifact-id string?)
(s/def ::version string?)
(s/def ::scm-url string?)

;; Entity maps -------------------------------------------------------
;; These are basically intended to serve the same purpose as
;; grimoire.things but in a plain data, cross platform fashion

(s/def ::group-entity
  (s/keys :req-un [::group-id]))

(s/def ::artifact-entity
  (s/merge ::group-entity
           (s/keys :req-un [::artifact-id])))

(s/def ::version-entity
  (s/merge ::artifact-entity
           (s/keys :req-un [::version])))

(s/def ::namespace-entity
  (s/merge ::version-entity
           (s/keys :req-un [::namespace])))

(s/def ::def string?)
(s/def ::def-entity
  (s/merge ::namespace-entity
           (s/keys :req-un [::def])))

(s/def ::grimoire-entity
  (s/or :group ::group-entity
        :artifact ::artifact-entity
        :version ::version-entity
        :namespace ::namespace-entity
        :def ::def-entity))


;; Cache specific ----------------------------------------------------

(s/def ::cache-contents
  (s/coll-of ::def-full :gen-max 2))

(s/def ::cache-id ::grimoire-entity
  ;; TODO figure out where to put scm-url and other metadata that
  ;; does not directly serve as identifier
  #_(s/keys :req-un [::group-id ::artifact-id ::version ::scm-url]))

(s/def ::cache-bundle
  ;; Not using 'id' and 'contents' as keys here because
  ;; this map is intended as part of the API and explicitly
  ;; mentioning that this data is related to the cache may
  ;; help them to understand the API faster
  (s/keys :req-un [::cache-id ::cache-contents]))

(comment
  (require '[clojure.spec.gen.alpha :as gen])

  (gen/sample (s/gen ::def))
  (gen/sample (s/gen ::def-full))

  (clojure.pprint/pprint
   (first (gen/sample (s/gen ::cache-bundle))))

  (gen/sample (s/gen ::namespace))
  (gen/sample (s/gen ::artifact))

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

  (s/valid? ::namespace x)



  )

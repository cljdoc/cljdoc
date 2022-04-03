(ns cljdoc.spec
  (:refer-clojure :exclude [assert])
  (:require [clojure.spec.alpha :as s]
            [malli.core]
            [malli.error]))

;;
;; API data
;;

(s/def ::name string?)
(s/def ::doc (s/nilable string?))
(s/def ::src string?)
(s/def ::type #{:var :fn :macro :protocol :multimethod})
(s/def ::line (s/and int? pos?))

;; minival var definition
(s/def ::def-minimal
  (s/keys :req-un [::name ::type]
          :opt-un [::doc
                   ::src
                   ::line])) ; may not be present in vars that have been defined dynamically

(s/def ::platform #{"clj" "cljs"})
(s/def ::namespace string?)

;; hydrated var definition
(s/def ::def-full
  (s/merge ::def-minimal
           (s/keys :req-un [::platform ::namespace])))

;;
;; Library data
;;

(s/def ::group-id string?)
(s/def ::artifact-id string?)
(s/def ::version string?)

(s/def ::group-entity
  (s/keys :req-un [::group-id]))

;; group-id + artifact-id
(s/def ::artifact-entity
  (s/merge ::group-entity (s/keys :req-un [::artifact-id])))

;; group-id + artifact-id + version-id
(s/def ::version-entity
  (s/merge ::artifact-entity (s/keys :req-un [::version])))

;; group-id + artifact-id + version-id + platform
(s/def ::platform-entity
  (s/merge ::version-entity (s/keys :req-un [::platform])))

;; group-id + artifact-id + version-id + namespace
(s/def ::namespace-entity
  (s/merge ::version-entity (s/keys :req-un [::namespace])))

(s/def ::def string?)
;; group-id + artifact-id + version-id + namespace + def
(s/def ::def-entity
  (s/merge ::namespace-entity (s/keys :req-un [::def])))

;; TODO: definitely rename
;; TODO: what is this and why do we need it?
(s/def ::grimoire-entity
  ;; Ordering matters for conforming
  (s/or :def        ::def-entity
        :namespace  ::namespace-entity
        :platform   ::platform-entity
        :version    ::version-entity
        :artifact   ::artifact-entity
        :group      ::group-entity))

;;
;; Cache specific ----------------------------------------------------
;;

;;
;; Docs-cache: this is intended for a specific
;; versioned artifact, e.g. [re-frame 0.10.5]
;;

(s/def ::defs (s/coll-of ::def-full :gen-max 2))
(s/def ::namespaces (s/coll-of map? :gen-max 2))
(s/def ::latest ::version)

(s/def ::docs-cache
  (s/keys :req-un [::defs ::namespaces ::latest]))

(s/def :cache/artifacts (s/coll-of string?))
(s/def :cache/versions (s/coll-of (s/keys :req-un [::version ::artifact-id])))
(s/def ::group-cache
  (s/keys :req-un [:cache/versions :cache/artifacts]))

;;
;; Cache bundle (combination of the above cache specs)
;;

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

;;
;; search ----------------------------------------------------------
;;

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
    (throw (Exception. (str "No spec found for " spec)))))

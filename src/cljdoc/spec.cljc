(ns cljdoc.spec
  (:refer-clojure :exclude [assert])
  (:require [clojure.spec.alpha :as s]))

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

;;
;; searchset ----------------------------------------------------------
;;

;; namespaces
(s/def :searchset-namespace/name string?)
(s/def :searchset-namespace/doc ::doc)
(s/def :searchset-namespace/path string?)
(s/def :searchset-namespace/platform ::platform)
(s/def :searchset/namespace (s/keys :req-un [:searchset-namespace/name
                                             :searchset-namespace/doc
                                             :searchset-namespace/path
                                             :searchset-namespace/platform]))
(s/def :searchset/namespaces (s/coll-of :searchset/namespace))

;; defs
(s/def :searchset-def/platform ::platform)
(s/def :searchset-def/type ::type)
(s/def :searchset-def/namespace ::namespace)
(s/def :searchset-def/name string?)
(s/def :searchset-def/arglist (s/coll-of symbol?))
(s/def :searchset-def/arglists (s/coll-of :searchset-def/arglist))
(s/def :searchset-def-member/type ::type)
(s/def :searchset-def-member/name symbol?)
(s/def :searchset-def-member/arglists :searchset-def/arglists)
(s/def :searchset-def-member/doc ::doc)
(s/def :searchset-def/member (s/keys :req-un [:searchset-def-member/type
                                              :searchset-def-member/name
                                              :searchset-def-member/arglists
                                              :searchset-def-member/doc]))
(s/def :searchset-def/members (s/coll-of :searchset-def/member))
(s/def :searchset-def/path string?)
(s/def :searchset-def/with-members (s/keys :req-un [:searchset-def/platform
                                                    :searchset-def/type
                                                    :searchset-def/namespace
                                                    :searchset-def/name
                                                    :searchset-def/members
                                                    :searchset-def/path]
                                           :opt-un [:searchset-def/doc]))
(s/def :searchset-def/with-arglists (s/keys :req-un [:searchset-def/platform
                                                     :searchset-def/type
                                                     :searchset-def/namespace
                                                     :searchset-def/name
                                                     :searchset-def/arglists
                                                     :searchset-def/doc
                                                     :searchset-def/path]))

(s/def :searchset/def (s/or :def-with-members :searchset-def/with-members
                            :def-with-arglists :searchset-def/with-arglists))
(s/def :searchset/defs (s/coll-of :searchset/def))

;; docs
(s/def :searchset-doc/name ::name)
(s/def :searchset-doc/path string?)
(s/def :searchset-doc/doc string?)
(s/def :searchset/doc (s/keys :req-un [:searchset-doc/name
                                       :searchset-doc/path
                                       :searchset-doc/doc]))
(s/def :searchset/docs (s/coll-of :searchset/doc))

;; searchset
(s/def :cljdoc/searchset (s/keys :req-un [:searchset/namespaces
                                          :searchset/defs
                                          :searchset/docs]))

;; utilities ----------------------------------------------------------

(defn assert [spec v]
  (if (s/get-spec spec)
    (s/assert spec v)
    (throw (Exception. (str "No spec found for " spec)))))

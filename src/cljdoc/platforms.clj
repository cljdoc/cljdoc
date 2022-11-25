(ns cljdoc.platforms
  "Utilities to work with API information about different platforms

  Most of the time a function/namespace will have identical metadata
  when defined in Clojure and ClojureScript but sometimes they don't.

  This namespace provides a `MultiPlatform` record that aims to expose
  a thing (namespace, var)'s properties while also retaining and exposing
  information about platform differences."
  (:require [clojure.tools.logging :as log]
            [cljdoc.spec]
            [clojure.string :as string]))

(defprotocol IMultiPlatform
  (get-field [this k] [this k platf]
    "Get a field `k` and throw if the fields value varies across all provided platforms.
    If `platf` is provided return field `k` as specified by platform `platf`.")
  (varies? [this k] "Return true if there are more than 1 unique values for field `k`")
  (platforms [this] "Return a set of all platforms this thing supports.")
  (all-vals [this k] "Return all non-nil values for the provided field `k`."))

(declare unify-defs)

(defrecord MultiPlatform [platforms]
  IMultiPlatform
  (get-field [this k]
    (let [ignore-nil #{:doc}
          uniq-vals (set ((if (ignore-nil k) keep map) k platforms))]
      (cond
        ;; a single protocol's member's can be defined differently for different platforms
        (= k :members)
        (let [members (for [p platforms
                            m (:members p)]
                        (assoc m :platform (:platform p)))]
          (->> members
               (map #(dissoc % :file :line))
               (group-by :name)
               vals
               (sort-by #(some-> % :name string/lower-case))
               (map unify-defs)))

        (not (second uniq-vals))
        (first uniq-vals)

        :else
        (do (log/warnf "Varying %s %s <> %s: %s/%s" k (first uniq-vals) (second uniq-vals)
                       (get-field this :namespace) (get-field this :name))
            (get-field this k "cljs")))))
  (get-field [_this k platf]
    (assert (contains? #{"clj" "cljs"} platf) (format "unknown platform: %s" platf))
    (-> (filter #(= platf (:platform %)) platforms)
        (first)
        (get k)))
  (varies? [_this k]
    (< 1 (count (set (map k platforms)))))
  (platforms [_this]
    (set (map :platform platforms)))
  (all-vals [_this k]
    (keep k platforms)))

(defn multiplatform? [x]
  (instance? MultiPlatform x))

(defn unify-defs
  "Takes a series of maps describing a single var across multiple
  platforms. Returns an instance of the MultiPlatform record that
  allows accessing fields in a platform-aware manner."
  [platforms]
  ;; todo assert names are equal
  (doseq [p platforms]
    (cljdoc.spec/assert :cljdoc.spec/def-full p))
  (->MultiPlatform platforms))

(defn unify-namespaces [platforms]
  (->MultiPlatform platforms))

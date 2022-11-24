(ns cljdoc.platforms
  "Utilities to work with API information about different platforms

  Most of the time a function/namespace will have identical metadata
  when defined in Clojure and ClojureScript but sometimes they don't.

  This namespace provides a `MultiPlatform` record that aims to expose
  a thing (namespace, var)'s properties while also retaining and exposing
  information about platform differences."
  (:require [clojure.tools.logging :as log]
            [cljdoc.spec]))

(defprotocol IMultiPlatform
  (get-field [this k] [this k platf]
    "Get a field `k` and throw if the fields value varies across all provided platforms.
    If `platf` is provided return field `k` as specified by platform `platf`.")
  (varies? [this k] "Return true if there are more than 1 unique values for field `k`")
  (platforms [this] "Return a set of all platforms this thing supports.")
  (all-vals [this k] "Return all non-nil values for the provided field `k`."))

(defrecord MultiPlatform [platforms]
  IMultiPlatform
  (get-field [this k]
    (let [ignore-nil #{:doc}
          uniq-vals (set ((if (ignore-nil k) keep map) k platforms))]
      (if-not (second uniq-vals)
        (first uniq-vals)
        ;; Current codox doesn't return consistent results for mulitmethods that are defined
        ;; by running a function, i.e. (defn x [a] (defmulti b first))
        ;; This is kind of an unusual case but there are libraries doing that and until this issue
        ;; is fixed we don't want to break docs for those libraries completetly, see precept 0.5.0-alpha for an example
        ;; https://github.com/CoNarrative/precept/blob/73113feec5bff11f5195261a81a015f882544614/src/cljc/precept/core.cljc#L356
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
  (let [clean-members (fn clean-members [members]
                        (->> members
                             (sort-by :name)
                             (map #(dissoc % :file :line))))]
    ;; todo assert names are equal
    (doseq [p platforms]
      (cljdoc.spec/assert :cljdoc.spec/def-full p))
    (->> platforms
         (map #(update % :members clean-members))
         (->MultiPlatform))))

(defn unify-namespaces [platforms]
  (->MultiPlatform platforms))

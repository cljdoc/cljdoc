(ns cljdoc.platforms
  "Utilities to work with API information about different platforms

  Most of the time a function/namespace will have identical metadata
  when defined in Clojure and ClojureScript but sometimes they don't.

  This namespace provides a `MultiPlatform` record that aims to expose
  a thing (namespace, var)'s properties while also retaining and exposing
  information about platform differences."
  (:require [cljdoc.spec]))

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
      (assert (not (< 1 (count uniq-vals))) (format "%s varies: %s" k (pr-str platforms)))
      (first uniq-vals)))
  (get-field [this k platf]
    (-> (filter #(= platf (:platform %)) platforms)
        (first)
        (get k)))
  (varies? [this k]
    (< 1 (count (set (map k platforms)))))
  (platforms [this]
    (set (map :platform platforms)))
  (all-vals [this k]
    (keep k platforms)))

(defn multiplatform? [x]
  (instance? MultiPlatform x))

(defn unify-defs [platforms]
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

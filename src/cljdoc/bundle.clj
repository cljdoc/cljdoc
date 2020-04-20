(ns cljdoc.bundle
  "Functions to operate on cache bundles"
  (:require [cljdoc.spec]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.platforms :as platf]
            [clojure.string :as string]))

(defn ns-entities
  "Return entity-maps for all namespaces in the cache-bundle"
  [{:keys [version-entity] :as cache-bundle}]
  (let [nss-from-defs (set (map :namespace (:defs cache-bundle)))
        nss-with-doc  (set (map :name (filter :doc (:namespaces cache-bundle))))
        has-defs?     (fn [{:keys [namespace]}]
                        (or (contains? nss-from-defs namespace)
                            (contains? nss-with-doc namespace)))]
    (->> (:namespaces cache-bundle)
         (map #(merge (:version-entity %) {:namespace (:name %)}))
         (filter has-defs?)
         (map #(cljdoc.spec/assert :cljdoc.spec/namespace-entity %))
         set)))

(defn- platf-name [p]
  (string/lower-case (platf/get-field p :name)))

(defn namespaces
  [{:keys [version-entity] :as cache-bundle}]
  {:pre [(find cache-bundle :namespaces)]}
  (->> (:namespaces cache-bundle)
       (group-by :name)
       (vals)
       (map platf/unify-namespaces)
       (sort-by platf-name)))

(defn get-namespace [bundle ns]
 (first (filter #(= ns (platf/get-field % :name)) (namespaces bundle))))

(defn scm-info [bundle]
  (-> bundle :version :scm))

(defn scm-url [bundle]
  (-> bundle scm-info :url))

(defn all-defs [bundle]
  (:defs bundle))

(defn defs-for-ns
  [some-defs ns]
  (->> some-defs
       (filter #(= ns (:namespace %)))
       (group-by :name)
       (vals)
       (map platf/unify-defs)
       (sort-by platf-name)))

(defn- add-src-uri
  [{:keys [platforms] :as mp-var} scm-base file-mapping]
  {:pre [(platf/multiplatform? mp-var)]}
  (if file-mapping
    (->> platforms
         (map (fn [{:keys [file line] :as p}]
                (assoc p :src-uri (str scm-base (get file-mapping file) "#L" line))))
         (assoc mp-var :platforms))
    mp-var))

(defn defs-for-ns-with-src-uri
  [bundle ns]
  (let [defs         (defs-for-ns (all-defs bundle) ns)
        scm-info     (scm-info bundle)
        blob         (or (:name (:tag scm-info)) (:commit scm-info))
        scm-base     (str (:url scm-info) "/blob/" blob "/")
        file-mapping (when (:files scm-info)
                       (fixref/match-files
                        (keys (:files scm-info))
                        (set (mapcat #(platf/all-vals % :file) defs))))]
    (map #(add-src-uri % scm-base file-mapping) defs)))

(defn more-recent-version
  [{:keys [version-entity] :as cache-bundle}]
  (when (and (:latest cache-bundle)
             (not= (:version version-entity) (:latest cache-bundle)))
    (assoc version-entity :version (:latest cache-bundle))))

(comment
  (defn cb [id]
    (cljdoc.storage.api/bundle-docs (cljdoc.storage.api/->GrimoireStorage (clojure.java.io/file "data" "grimoire")) id))

  (->> (cb {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"})
       namespaces)

  )

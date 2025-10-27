(ns cljdoc.docset
  "Functions to operate on docsets"
  (:require [cljdoc.platforms :as platf]
            [cljdoc.spec]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.util.scm :as scm]
            [clojure.string :as string]))

(defn ns-entities
  "Return entity-maps for all namespaces in the docset"
  [docset]
  (let [nss-from-defs (set (map :namespace (:defs docset)))
        nss-with-doc  (set (map :name (filter :doc (:namespaces docset))))
        has-defs?     (fn [{:keys [namespace]}]
                        (or (contains? nss-from-defs namespace)
                            (contains? nss-with-doc namespace)))]
    (->> (:namespaces docset)
         (map #(merge (:version-entity %) {:namespace (:name %)}))
         (filter has-defs?)
         (map #(cljdoc.spec/assert :cljdoc.spec/namespace-entity %))
         set)))

(defn- platf-name [p]
  (string/lower-case (platf/get-field p :name)))

(defn namespaces
  [docset]
  {:pre [(find docset :namespaces)]}
  (->> (:namespaces docset)
       (group-by :name)
       (vals)
       (map platf/unify-namespaces)
       (sort-by platf-name)))

(defn get-namespace [docset ns]
  (first (filter #(= ns (platf/get-field % :name)) (namespaces docset))))

(defn scm-info [docset]
  (-> docset :version :scm))

(defn articles-scm-info [docset]
  (or (-> docset :version :scm-articles)
      (scm-info docset)))

(defn scm-url [docset]
  (-> docset scm-info :url))

(defn all-defs [docset]
  (:defs docset))

(defn defs-for-ns
  [some-defs ns]
  (->> some-defs
       (filter #(= ns (:namespace %)))
       (group-by :name)
       (vals)
       (map platf/unify-defs)
       (sort-by platf-name)))

(defn- add-src-uri
  [{:keys [platforms] :as mp-var} scm-base line-anchor file-mapping]
  {:pre [(platf/multiplatform? mp-var)]}
  (if file-mapping
    (->> platforms
         (map (fn [{:keys [file line] :as p}]
                (assoc p :src-uri (str scm-base (get file-mapping file) line-anchor line))))
         (assoc mp-var :platforms))
    mp-var))

(defn defs-for-ns-with-src-uri
  [docset ns]
  (let [defs         (defs-for-ns (all-defs docset) ns)
        scm-info     (scm-info docset)
        scm-base     (scm/rev-formatted-base-url scm-info)
        line-anchor  (scm/line-anchor scm-info)
        file-mapping (when (:files scm-info)
                       (fixref/match-files
                        (keys (:files scm-info))
                        (set (mapcat #(platf/all-vals % :file) defs))))]
    (map #(add-src-uri % scm-base line-anchor file-mapping) defs)))

(defn more-recent-version
  [{:keys [version-entity] :as docset}]
  (when (and (:latest docset)
             (not= (:version version-entity) (:latest docset)))
    (assoc version-entity :version (:latest docset))))


(ns cljdoc.bundle
  "Functions to operate on cache bundles"
  (:require [cljdoc.spec]
            [cljdoc.util.fixref :as fixref]
            [cljdoc.platforms :as platf]
            [clojure.string :as string]))

(defn ns-entities
  "Return entity-maps for all namespaces in the cache-bundle"
  [{:keys [cache-contents cache-id]}]
  (let [has-defs? (fn [ns-emap]
                    (seq (filter #(= (:namespace ns-emap) (:namespace %))
                                 (:defs cache-contents))))]
    (->> (:namespaces cache-contents)
         (map :name)
         (map #(merge cache-id {:namespace %}))
         (filter has-defs?)
         (map #(cljdoc.spec/assert :cljdoc.spec/namespace-entity %))
         set)))

(defn- platf-name [p]
  (string/lower-case (platf/get-field p :name)))

(defn namespaces
  [{:keys [cache-contents cache-id]}]
  {:pre [(find cache-contents :namespaces)]}
  (->> (:namespaces cache-contents)
       (group-by :name)
       (vals)
       (map platf/unify-namespaces)
       (sort-by platf-name)))

(defn defs-for-ns
  [all-defs ns]
  (->> all-defs
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
  [defs scm-info ns]
  (let [defs         (defs-for-ns defs ns)
        blob         (or (:name (:tag scm-info)) (:commit scm-info))
        scm-base     (str (:url scm-info) "/blob/" blob "/")
        file-mapping (when (:files scm-info)
                       (fixref/match-files
                        (keys (:files scm-info))
                        (set (mapcat #(platf/all-vals % :file) defs))))]
    (map #(add-src-uri % scm-base file-mapping) defs)))

(defn more-recent-version
  [{:keys [cache-contents cache-id]}]
  (when (and (:latest cache-contents)
             (not= (:version cache-id) (:latest cache-contents)))
    (assoc cache-id :version (:latest cache-contents))))

(comment
  (defn cb [id]
    (cljdoc.storage.api/bundle-docs (cljdoc.storage.api/->GrimoireStorage (clojure.java.io/file "data" "grimoire")) id))

  (->> (cb {:group-id "re-frame" :artifact-id "re-frame" :version "0.10.5"})
       namespaces)

  )

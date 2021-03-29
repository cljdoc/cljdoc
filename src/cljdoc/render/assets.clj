(ns cljdoc.render.assets
  "Fetch 3rd party render assets for both cljdoc site and for downloaded offline bundles.

   See resources/asset-deps.edn for 3rd party css and js assets."
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.tools.reader.edn :as edn]
            [clojure.walk :as walk]))

(defn- asset-dep [asset-id]
  (let [dep (-> "asset-deps.edn"
                io/resource
                slurp
                edn/read-string
                asset-id)]
    (walk/postwalk #(if (string? %)
                      (string/replace % "{{:version}}" (:version dep))
                      %)
                   dep)))

(defn- offline-asset-path [{:keys [asset-id asset]}]
  (str "assets/" (name asset-id) "/" asset))

(defn- online-asset-path [{:keys [root asset]}]
  (str root asset))

(defn- assets
  [asset-id asset-type url-fn]
  (if-let [asset-type-def (asset-type (asset-dep asset-id))]
    (mapv #(url-fn {:asset-id asset-id
                    :root (:root asset-type-def)
                    :asset  %})
          (:assets asset-type-def))
    (throw (ex-info (format "html asset not found for asset-id: %s type: %s" asset-id asset-type) {}))))

(defn- strip-query-string [url]
  (if-let [ndx (string/index-of url "?")]
    (subs url 0 ndx)
    url))

(defn- offline-assets-for-type
  [asset-id asset-def asset-type]
  (when-let [asset-type-def (asset-type asset-def)]
    (mapv (fn [asset]
            (let [asset (strip-query-string asset)
                  path-def {:asset-id asset-id
                            :root (:root asset-type-def)
                            :asset asset}]
              [(offline-asset-path path-def)
               (io/as-url (online-asset-path path-def))]))
          (concat (:assets asset-type-def)
                  (:assets-offline-download-only asset-type-def)))))

(defn js [asset-id]
  (assets asset-id :js online-asset-path))

(defn css [asset-id]
  (assets asset-id :css online-asset-path))

(defn offline-js [asset-id]
  (assets asset-id :js offline-asset-path))

(defn offline-css [asset-id]
  (assets asset-id :css offline-asset-path))

(defn offline-assets
  "Return a vector of [zip-fname remote-url]"
  [asset-id]
  (if-let [asset-def (asset-dep asset-id)]
    (mapcat #(offline-assets-for-type asset-id asset-def %) [:css :js])
    (throw (ex-info (format "html asset not found for asset-id: %s type: %s" asset-id type) {}))))


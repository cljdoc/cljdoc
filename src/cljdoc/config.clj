(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [clojure.java.io :as io]
            [aero.core :as aero]))

(defn get-in
  [config-map ks]
  (or (clojure.core/get-in config-map ks)
      (throw (ex-info (format "No config found for path %s" ks) {:ks ks}))))

(defn config
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile}))
  ([profile ks]
   (get-in (config profile) ks)))

(comment
  (config :default)

  (get-in (config :default)
          [:cljdoc/hardcoded (cljdoc.util/artifact-id project) :cljdoc.api/namespaces])

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default}))

  )

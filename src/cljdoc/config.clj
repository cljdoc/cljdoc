(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [aero.core :as aero]))

(defn get-in [config-map ks]
  (or (clojure.core/get-in config-map ks)
      (throw (ex-info (format "No config found for path %s" ks) {:ks ks}))))

(defn config
  ([profile]
   (aero/read-config "config.edn" {:profile profile}))
  ([profile ks]
   (get-in (config profile) ks)))

(ns cljdoc.config
  (:require [aero.core :as aero]))

(defn config
  ([profile]
   (aero/read-config "config.edn" {:profile profile}))
  ([profile ks]
   (or (-> (config profile) (get-in ks))
       (throw (ex-info (format "No config found for path %s" ks) {:ks ks})))))

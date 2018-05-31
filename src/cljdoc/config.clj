(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [aero.core :as aero]))

(defn profile []
  (let [known-profiles #{:live :local :prod}
        profile        (keyword (System/getenv "CLJDOC_PROFILE"))]
    (or (known-profiles profile)
        (log/warnf "No known profile found in CLJDOC_PROFILE %s" profile))))

(defn get-in
  [config-map ks]
  (if (some? (clojure.core/get-in config-map ks))
    (clojure.core/get-in config-map ks)
    (throw (ex-info (format "No config found for path %s\nDid you configure your secrets.edn file?" ks)
                    {:ks ks, :profile (profile)}))))

(defn config []
  (aero/read-config (io/resource "config.edn") {:profile (profile)}))

(defn circle-ci []
  {:api-token       (get-in (config) [:secrets :circle-ci :api-token])
   :builder-project (get-in (config) [:secrets :circle-ci :builder-project])})

(defn analysis-service []
  (get-in (config) [:cljdoc/server :analysis-service]))

(defn data-dir []
  (get-in (config) [:cljdoc/server :dir]))

(defn build-log-db []
  {:classname "org.sqlite.JDBC",
   :subprotocol "sqlite",
   :subname (str (data-dir) "build-log.db")})

(defn autobuild-clojars-releases? []
  (get-in (config) [:cljdoc/server :autobuild-clojars-releases?]))

(comment
  (config)

  (get-in (config)
          [:cljdoc/hardcoded (cljdoc.util/artifact-id project) :cljdoc.api/namespaces])

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default}))

  )

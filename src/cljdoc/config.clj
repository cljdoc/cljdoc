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

;; Accessors

(defn circle-ci
  ([] (circle-ci (config)))
  ([config]
   {:api-token       (get-in config [:secrets :circle-ci :api-token])
    :builder-project (get-in config [:secrets :circle-ci :builder-project])}))

(defn analysis-service
  ([] (analysis-service (config)))
  ([config] (get-in config [:cljdoc/server :analysis-service])))

(defn data-dir
  ([] (data-dir (config)))
  ([config] (get-in config [:cljdoc/server :dir])))

(defn build-log-db
  ([] (build-log-db (config)))
  ([config]
   {:classname "org.sqlite.JDBC",
    :subprotocol "sqlite",
    :subname (str (data-dir config) "build-log.db")}))

(defn autobuild-clojars-releases?
  ([] (autobuild-clojars-releases? (config)))
  ([config] (get-in config [:cljdoc/server :autobuild-clojars-releases?])))

(defn version
  ([] (version (config)))
  ([config] (get-in config [:cljdoc/version])))

(defn sentry-dsn
  ([] (sentry-dsn (config)))
  ([config] (when (get-in config [:cljdoc/server :enable-sentry?])
              (get-in config [:secrets :sentry :dsn]))))

(comment
  (config)

  (get-in (config)
          [:cljdoc/hardcoded (cljdoc.util/artifact-id project) :cljdoc.api/namespaces])

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default}))

  )

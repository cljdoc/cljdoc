(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [aero.core :as aero]
            [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defmethod aero/reader 'slurp
  [_ _tag value]
  (aero/deferred
   (try (.trim (slurp (io/resource value)))
        (catch Exception e
          (throw (Exception. (str "Exception reading " value  " from classpath") e))))))

(defn profile []
  (let [known-profiles #{:live :local :prod :test :default nil}
        profile        (keyword (System/getenv "CLJDOC_PROFILE"))]
    (if (contains? known-profiles profile)
      profile
      (throw (ex-info (format "Unknown config profile: %s" profile) {})))))

(defn get-in
  [config-map ks]
  (if (some? (clojure.core/get-in config-map ks))
    (clojure.core/get-in config-map ks)
    (throw (ex-info (format "No config found for path %s\nDid you configure your secrets.edn file?" ks)
                    {:ks ks, :profile (profile)}))))

(defn- config-file []
  (or (some-> (System/getenv "CLJDOC_CONFIG_EDN") fs/file)
      (io/resource "config.edn")))

(defn- config-override-map
  "Used for staging, when we sometimes want to tweak config."
  []
  (or (some-> (System/getenv "CLJDOC_CONFIG_OVERRIDE_MAP") edn/read-string)
      {}))

(defn- deep-merge
  [& maps]
  (if (every? map? maps)
    (apply merge-with deep-merge maps)
    (last maps)))

(defn- read-config [f profile]
  (aero/read-config f {:profile profile}))

(defn config []
  (deep-merge (aero/read-config (config-file) {:profile (profile)})
              (config-override-map)))

;; Accessors

(defn version
  ([] (version (config)))
  ([config] (get-in config [:cljdoc/version])))

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

(defn autobuild-clojars-releases? [config]
  (get-in config [:cljdoc/server :autobuild-clojars-releases?]))

(defn enable-release-monitor? [config]
  (not (clojure.core/get-in config [:cljdoc/server :disable-release-monitor?])))

(defn enable-db-restore? [config]
  (get-in config [:cljdoc/server :enable-db-restore?]))

(defn enable-db-backup? [config]
  (get-in config [:cljdoc/server :enable-db-backup?]))

(defn- backup-restore-secrets [config]
  (-> config
      :secrets
      :s3
      :backups))

(defn db-backup [config]
  (let [enabled? (enable-db-backup? config)]
    (cond-> {:enable-db-backup? enabled?}
      enabled? (merge (backup-restore-secrets config)))))

(defn db-restore [config]
  (let [enabled? (enable-db-restore? config)]
    (cond-> {:enable-db-restore? enabled?}
      enabled? (merge (backup-restore-secrets config)))))

(defn sentry-dsn
  ([] (sentry-dsn (config)))
  ([config] (when (get-in config [:cljdoc/server :enable-sentry?])
              (get-in config [:secrets :sentry :dsn]))))

(defn maven-repositories []
  (get-in (config) [:maven-repositories]))

(defn extension-namespaces [config]
  (clojure.core/get-in config [:extension-namespaces]))

(defn enable-artifact-indexer? [config]
  (not (clojure.core/get-in config [:cljdoc/server :disable-artifact-indexer??])))

(comment
  (:cljdoc/server (config))

  (sentry-dsn)

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default})))

(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [aero.core :as aero]))

(defmethod aero/reader 'slurp
  [_ tag value]
  (aero/deferred
    (try (.trim (slurp (io/resource value)))
         (catch Exception e
           (throw (Exception. (str "Exception reading " value  " from classpath") e))))))

(defn profile []
  (let [known-profiles #{:live :local :prod :test nil}
        profile        (keyword (System/getenv "CLJDOC_PROFILE"))]
    (if (contains? known-profiles profile)
      profile
      (log/warnf "No known profile found in CLJDOC_PROFILE %s" profile))))

(defn get-in
  [config-map ks]
  (if (some? (clojure.core/get-in config-map ks))
    (clojure.core/get-in config-map ks)
    (throw (ex-info (format "No config found for path %s\nDid you configure your secrets.edn file?" ks)
                    {:ks ks, :profile (profile)}))))

(defn config
  ([] (aero/read-config (io/resource "config.edn") {:profile (profile)}))
  ([profile] (aero/read-config (io/resource "config.edn") {:profile profile})))

;; Accessors

(defn version
  ([] (version (config)))
  ([config] (get-in config [:cljdoc/version])))

(defn circle-ci
  ([] (circle-ci (config)))
  ([config]
   {:api-token       (get-in config [:secrets :circle-ci :api-token])
    :builder-project (get-in config [:secrets :circle-ci :builder-project])
    ;; Instead of using the version currently running we could also
    ;; retrieve the latest commit on master from Github directly.
    ;; This would allow us to update the analyzer without
    ;; redeploying the cljdoc server. See GitHub API:
    ;; https://developer.github.com/v3/repos/commits/
    :analyzer-version (version config)}))

(defn analysis-service
  ([] (analysis-service (config)))
  ([config] (get-in config [:cljdoc/server :analysis-service])))

(defn data-dir
  ([] (data-dir (config)))
  ([config] (get-in config [:cljdoc/server :dir])))

(defn statsd
  [config]
  (get config :statsd))

(defn db
  [config]
  {:classname "org.sqlite.JDBC",
   :subprotocol "sqlite",
   :foreign_keys true
   :cache_size 10000
   :subname (let [new-path (str (data-dir config) "cljdoc.db.sqlite")
                  old-path (str (data-dir config) "build-log.db")]
              (if (.exists (io/file old-path))
                (do (log/warnf "Database needs to be moved from %s to %s" old-path new-path)
                    old-path)
                new-path))
   ;; These settings are permanent but it seems like
   ;; this is the easiest way to set them. In a migration
   ;; they fail because they return results.
   :synchronous "NORMAL"
   :journal_mode "WAL"})

(defn autobuild-clojars-releases?
  ([] (autobuild-clojars-releases? (config)))
  ([config] (get-in config [:cljdoc/server :autobuild-clojars-releases?])))

(defn sentry-dsn
  ([] (sentry-dsn (config)))
  ([config] (when (get-in config [:cljdoc/server :enable-sentry?])
              (get-in config [:secrets :sentry :dsn]))))

(comment
  (:cljdoc/server (config))

  (sentry-dsn)

  (get-in (config)
          [:cljdoc/hardcoded (cljdoc.util/artifact-id project) :cljdoc.api/namespaces])

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default}))

  )

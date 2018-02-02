(ns cljdoc.config
  (:refer-clojure :exclude [get-in])
  (:require [clojure.java.io :as io]
            [aero.core :as aero]))

(defn get-in
  [config-map ks]
  (or (clojure.core/get-in config-map ks)
      (throw (ex-info (format "No config found for path %s\nDid you configure your secrets.edn file?" ks) {:ks ks}))))

(defn config
  ([profile]
   (aero/read-config (io/resource "config.edn") {:profile profile}))
  ([profile ks]
   (get-in (config profile) ks)))

(defn circle-ci []
  {:api-token (get-in (config :default) [:secrets :circle-ci :api-token])
   :builder-project (get-in  (config :default) [:circle-ci :builder-project])})

(defn s3-deploy []
  {:access-key    (config :default [:secrets :aws :access-key])
   :secret-key    (config :default [:secrets :aws :secret-key])
   :cloudfront-id (config :default [:secrets :aws :cloudfront-id])
   :bucket        (config :default [:secrets :aws :s3-bucket-name])})

(comment
  (config :default)

  (get-in (config :default)
          [:cljdoc/hardcoded (cljdoc.util/artifact-id project) :cljdoc.api/namespaces])

  (clojure.pprint/pprint
   (aero/read-config (io/resource "config.edn") {:profile :default}))

  )

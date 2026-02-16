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

(defn config
  "Do not reference config directly. Rely on it being injected by integrant.
  See cljdoc.server.system/system-config & cljdoc.cli.

  One exception is logging, which needs to be up earlier than integrant,
  see cljdoc.log.init and cljdoc.log.sentry."
  []
  (deep-merge (aero/read-config (config-file) {:profile (profile)})
              (config-override-map)))

(comment
  (get-in (config) [:cljdoc/server :port])
  ;; => 8000

  (aero/read-config (io/resource "config.edn") {:profile :default})
  ;; => {:secrets {},
  ;;     :maven-repos
  ;;     [{:id "clojars", :url "https://repo.clojars.org/"}
  ;;      {:id "central", :url "https://repo.maven.apache.org/maven2/"}],
  ;;     :cljdoc/version "dev",
  ;;     :cljdoc/server
  ;;     {:clojars-stats-retention-days 20,
  ;;      :enable-db-restore? false,
  ;;      :dir "data/",
  ;;      :analysis-service :local,
  ;;      :opensearch-base-url "http://localhost:8000",
  ;;      :port 8000,
  ;;      :host "localhost",
  ;;      :enable-db-backup? false,
  ;;      :enable-sentry? false,
  ;;      :autobuild-clojars-releases? false},
  ;;     :cljdoc/hardcoded
  ;;     {"yada/yada"
  ;;      #:cljdoc.doc{:tree
  ;;                   [["Readme" {:file "README.md"}]
  ;;                    ["Preface" {:file "doc/preface.adoc"}]
  ;;                    ["Basics"
  ;;                     {}
  ;;                     ["Introduction" {:file "doc/intro.adoc"}]
  ;;                     ["Getting Started" {:file "doc/getting-started.adoc"}]
  ;;                     ["Hello World" {:file "doc/hello.adoc"}]
  ;;                     ["Installation" {:file "doc/install.adoc"}]
  ;;                     ["Resources" {:file "doc/resources.adoc"}]
  ;;                     ["Parameters" {:file "doc/parameters.adoc"}]
  ;;                     ["Properties" {:file "doc/properties.adoc"}]
  ;;                     ["Methods" {:file "doc/methods.adoc"}]
  ;;                     ["Representations" {:file "doc/representations.adoc"}]
  ;;                     ["Responses" {:file "doc/responses.adoc"}]
  ;;                     ["Security" {:file "doc/security.adoc"}]
  ;;                     ["Routing" {:file "doc/routing.adoc"}]
  ;;                     ["Phonebook" {:file "doc/phonebook.adoc"}]
  ;;                     ["Swagger" {:file "doc/swagger.adoc"}]]
  ;;                    ["Advanced Topics"
  ;;                     {}
  ;;                     ["Async" {:file "doc/async.adoc"}]
  ;;                     ["Search Engine" {:file "doc/searchengine.adoc"}]
  ;;                     ["Server Sent Events" {:file "doc/sse.adoc"}]
  ;;                     ["Chat Server" {:file "doc/chatserver.adoc"}]
  ;;                     ["Handling Request Bodies" {:file "doc/requestbodies.adoc"}]
  ;;                     ["Selfie Uploader" {:file "doc/selfieuploader.adoc"}]
  ;;                     ["Handlers" {:file "doc/handlers.adoc"}]
  ;;                     ["Request Context" {:file "doc/requestcontext.adoc"}]
  ;;                     ["Interceptors" {:file "doc/interceptors.adoc"}]
  ;;                     ["Subresources" {:file "doc/subresources.adoc"}]
  ;;                     ["Fileserver" {:file "doc/fileserver.adoc"}]
  ;;                     ["Testing" {:file "doc/testing.adoc"}]]
  ;;                    ["Reference"
  ;;                     {}
  ;;                     ["Glossary" {:file "doc/glossary.adoc"}]
  ;;                     ["Reference" {:file "doc/reference.adoc"}]
  ;;                     ["Colophon" {:file "doc/colophon.adoc"}]]]},
  ;;      "dali/dali"
  ;;      #:cljdoc.doc{:tree
  ;;                   [["Readme" {:file "README.md"}]
  ;;                    ["Basic Syntax" {:file "doc/syntax.md"}]
  ;;                    ["Layout" {:file "doc/layout.md"}]
  ;;                    ["Pre-fabricated Elements" {:file "doc/prefab.md"}]
  ;;                    ["How To Do X" {:file "doc/howto.md"}]
  ;;                    ["Limitations" {:file "doc/limitations.md"}]
  ;;                    ["Version History" {:file "doc/history.md"}]]}}}

  :eoc)

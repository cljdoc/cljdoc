(ns cljdoc.cli
  (:require [cli-matic.core :as cli-matic]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [cljdoc.config :as config]
            [cljdoc.render.offline :as offline]
            [cljdoc.server.system :as system]
            [cljdoc.server.api :as api]
            [cljdoc.storage.api :as storage]
            [cljdoc.util :as util]
            [cljdoc.util.repositories :as repositories]))

(defn build [{:keys [project version jar pom git rev] :as args}]
  (let [sys        (select-keys (system/system-config (config/config))
                                [:cljdoc/storage :cljdoc/build-tracker :cljdoc/analysis-service :cljdoc/sqlite])
        sys        (ig/init sys)
        deps       {:storage (:cljdoc/storage sys)
                    :build-tracker (:cljdoc/build-tracker sys)
                    :analysis-service (:cljdoc/analysis-service sys)}]
    (deref
     (:future
      (api/kick-off-build!
       deps
       (-> (merge (repositories/local-uris project version) args)
           (cset/rename-keys {:git :scm-url, :rev :scm-rev})))))))

(defn offline-bundle [{:keys [project version output] :as args}]
  (let [sys           (select-keys (system/system-config (config/config))
                                   [:cljdoc/storage :cljdoc/sqlite])
        sys           (ig/init sys)
        store         (:cljdoc/storage sys)
        artifact-info (util/version-entity project version)]
    (if (storage/exists? store artifact-info)
      (->
       (storage/bundle-docs store artifact-info)
       (offline/zip-stream)
       (io/copy (io/file output)))
      (do
        (log/fatalf "%s@%s could not be found in storage" project version)
        (System/exit 1)))))

(defn run [opts]
  (system/-main))

(def CONFIGURATION
  {:app         {:command     "cljdoc"
                 :description "command-line utilities to use cljdoc"
                 :version     "0.0.1"}

   :global-opts []

   :commands    [{:command     "ingest"
                  :description ["Ingest information about an artifact at a specific version"
                                ""
                                "By default this command will use jar/pom from local ~/.m2, download if needed."
                                "You may specify full paths to those files using the --jar and --pom options."
                                ""
                                "To test how a Git repository gets incorporated without pushing new release,"
                                "pass the --git option and use --rev to specify a revision to use (e.g. master)."]
                  :opts        [{:option "project" :short "p" :as "Project to import" :type :string :default :present}
                                {:option "version" :short "v" :as "Version to import" :type :string :default :present}
                                {:option "jar" :short "j" :as "Jar file to use (may be local)" :type :string}
                                {:option "pom" :as "POM file to use (may be local)" :type :string}
                                {:option "git" :short "g" :as "Git repo to use (may be local)" :type :string}
                                {:option "rev" :short "r" :as "Git revision to use (inferred by default)" :type :string}]
                  :runs        build}
                 {:command     "offline-bundle"
                  :description ["Builds an offline documentation bundle for previously ingested project"]
                  :opts        [{:option "project" :short "p" :as "Project to export" :type :string :default :present}
                                {:option "version" :short "v" :as "Version to export" :type :string :default :present}
                                {:option "output" :short "o" :as "Path of output zipfile" :type :string :default :present}]
                  :runs        offline-bundle}
                 {:command     "run"
                  :description "Run the cljdoc server (config in resources/config.edn)"
                  :opts        []
                  :runs        run}]})


(defn -main
  [& args]
  (cli-matic/run-cmd args CONFIGURATION))



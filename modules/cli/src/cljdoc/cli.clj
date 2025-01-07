(ns cljdoc.cli
  (:require [cli-matic.core :as cli-matic]
            [cljdoc.config :as config]
            [cljdoc.render.offline :as offline]
            [cljdoc.server.api :as api]
            [cljdoc.server.pedestal :as pedestal]
            [cljdoc.server.system :as system]
            [cljdoc.storage.api :as storage]
            [cljdoc.util.repositories :as repositories]
            [clojure.java.io :as io]
            [clojure.set :as cset]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

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

(defn offline-bundle [{:keys [project version output] :as _args}]
  (let [sys           (select-keys (system/system-config (config/config))
                                   [:cljdoc/storage :cljdoc/sqlite])
        sys           (ig/init sys)
        store         (:cljdoc/storage sys)
        artifact-info (storage/version-entity project version)
        static-resources (pedestal/build-default-static-resource-map)]
    (if (storage/exists? store artifact-info)
      (let [output (io/file output)]
        (-> (storage/bundle-docs store artifact-info)
            (offline/zip-stream static-resources)
            (io/copy output))
        (println "Offline bundle created:" (.getCanonicalPath output)))
      (do
        (log/fatalf "%s@%s could not be found in storage" project version)
        (System/exit 1)))))

(defn run [_opts]
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
                                "You may specify full paths to those files using the --jar and --pom options"
                                "(in this case --version may override version from pom)."
                                ""
                                "To test how a Git repository is ingested before pushing new release,"
                                "use the --git and --rev options to specify a revision to use (e.g. master)."]
                  :opts        [{:option "project" :short "p" :as "Project to import" :type :string :default :present}
                                {:option "version" :short "v" :as "Version to import" :type :string :default :present}
                                {:option "jar" :short "j" :as ["Jar file to use (local or remote)"
                                                               " default: inferred by maven repo lookup"] :type :string}
                                {:option "pom" :as ["POM file to use (local or remote)"
                                                    " default: inferred by maven repo lookup"] :type :string}
                                {:option "git" :short "g" :as ["Git repository  (local or remote)"
                                                               " default: inferred from pom.xml project/scm/url"] :type :string}
                                {:option "rev" :short "r" :as ["Git revision"
                                                               " default: inferred from pom.xml project/scm/tag,"
                                                               "          or if present, git tag representing --version"] :type :string}]
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

(ns cljdoc.cli
  (:require [clojure.java.io :as io]
            [cljdoc.config :as config]
            [cljdoc.util :as util]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.server.system :as system]
            [cljdoc.analysis.runner :as ana]
            [cljdoc.util.repositories :as repositories]
            [clojure.tools.logging :as log]
            [cljdoc.storage.api :as storage]
            [integrant.core :as ig]
            [cli-matic.core :as cli-matic]))

(defn build [{:keys [project version jar pom git rev]}]
  (let [sys          (select-keys (system/system-config (config/config)) [:cljdoc/sqlite])
        _            (ig/init sys)
        analysis-result (-> (ana/analyze-impl
                             (symbol project)
                             version
                             (or jar
                                 (:jar (repositories/local-uris project version))
                                 (:jar (repositories/artifact-uris project version)))
                             (or pom
                                 (:pom (repositories/local-uris project version))
                                 (:pom (repositories/artifact-uris project version))))
                            util/read-cljdoc-edn)
        storage  (storage/->SQLiteStorage (config/db (config/config)))
        scm-info (ingest/scm-info project (:pom-str analysis-result))]
    (ingest/ingest-cljdoc-edn storage analysis-result)
    (when (or (:url scm-info) git)
      (ingest/ingest-git! storage
                          {:project project
                           :version version
                           :scm-url (:url scm-info)
                           :local-scm git
                           :pom-revision (or rev (:sha scm-info))}))))

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
                 {:command     "run"
                  :description "Run the cljdoc server (config in resources/config.edn)"
                  :opts        []
                  :runs        run}]})


(defn -main
  [& args]
  (cli-matic/run-cmd args CONFIGURATION))



(ns cljdoc.analysis.runner-ng
  "Like the other runner namespace but instead of taking multiple positional arguments the
  -main of this namespace expects a single argument that will be parsed as EDN.

  In addition this runner also allows more customizations such as custom repositories
  and eventually namespace exclusions and more."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [cljdoc.util :as util]
            [cljdoc.analysis.deps :as deps]
            [cljdoc.analysis.runner :as runner]
            [cljdoc.spec]))

(defn -main
  "Analyze the provided project"
  [arg]
  (try
    (let [{:keys [project version jarpath pompath repos] :as args} (edn/read-string arg)
          {:keys [classpath resolved-deps]} (deps/resolved-and-cp pompath repos)]
      (println "Used dependencies for analysis:")
      (deps/print-tree resolved-deps)
      (println "---------------------------------------------------------------------------")
      (io/copy (#'runner/analyze-impl {:project (symbol project)
                                       :version version
                                       :jar jarpath
                                       :pom pompath
                                       :classpath classpath})
               (doto (io/file util/analysis-output-prefix (util/cljdoc-edn project version))
                 (-> .getParentFile .mkdirs))))
    (catch Throwable t
      (.printStackTrace t)
      (System/exit 1))
    (finally
      (shutdown-agents))))

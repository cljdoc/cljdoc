(ns cljdoc.analysis.runner-ng
  "Like the other runner namespace but instead of taking multiple positional arguments the
  -main of this namespace expects a single argument that will be parsed as EDN.

  In addition this runner also allows more customizations such as custom repositories
  and eventually namespace exclusions and more."
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [cljdoc.util :as util]
            [cljdoc.analysis.deps :as deps]
            [cljdoc.analysis.runner :as runner :refer [copy]]
            [cljdoc.spec]))

(defn -main
  "Analyze the provided project"
  [arg]
  (try
    (pp/pprint (edn/read-string arg))
    (let [{:keys [project version jarpath pompath repos] :as args} (edn/read-string arg)
          {:keys [classpath resolved-deps]} (deps/resolved-and-cp jarpath pompath repos)]
      (println "Used dependencies for analysis:")
      (deps/print-tree resolved-deps)
      (println "---------------------------------------------------------------------------")
      (copy (#'runner/analyze-impl {:project (symbol project)
                                    :version version
                                    :jar jarpath
                                    :pom pompath
                                    :classpath classpath})
            (io/file util/analysis-output-prefix (util/cljdoc-edn project version))))
    (catch Throwable t
      (.printStackTrace t)
      (System/exit 1))
    (finally
      (shutdown-agents))))

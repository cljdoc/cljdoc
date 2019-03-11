(ns cljdoc.analysis.runner-ng
  "Like the other runner namespace but instead of taking multiple positional arguments the
  -main of this namespace expects a single argument that will be parsed as EDN.

  In addition this runner also allows more customizations such as custom repositories
  and eventually namespace exclusions and more."
  (:require [clojure.pprint :as pp]
            [clojure.edn :as edn]
            [cljdoc.analysis.runner :as runner]))

(defn -main
  "Analyze the provided project"
  [edn-arg]
  (let [{:keys [project version jarpath pompath repos] :as args} (edn/read-string edn-arg)
        _                         (pp/pprint args)
        {:keys [analysis-status]} (runner/analyze! {:project project
                                                    :version version
                                                    :jarpath jarpath
                                                    :pompath pompath
                                                    :repos repos})]
    (shutdown-agents)
    (System/exit (if (= :success analysis-status) 0 1))))

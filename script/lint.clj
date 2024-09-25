#!/usr/bin/env bb

(ns lint
  (:require [babashka.fs :as fs]
            [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]))

(defn- build-cache []
  (shell/clojure "-T:build lint-cache"))

(defn- check-cache [{:keys [rebuild-cache]}]
  (status/line :head "clj-kondo: cache check")
  (if-let [rebuild-reason (cond
                            rebuild-cache
                            "Rebuild requested"

                            (not (fs/exists? ".clj-kondo/.cache"))
                            "Cache not found"

                            :else
                            (let [updated-dep-files (fs/modified-since ".clj-kondo/.cache"
                                                                       ["deps.edn" "bb.edn"])]
                              (when (seq updated-dep-files)
                                (format "Found deps files newer than lint cache: %s" (mapv str updated-dep-files)))))]
    (do (status/line :detail rebuild-reason)
        (build-cache))
    (status/line :detail "Using existing cache")))

(defn- lint [opts]
  (check-cache opts)
  (status/line :head "clj-kondo: linting")
  (let [{:keys [exit]}
        (shell/command {:continue true}
                       "clojure -M:clj-kondo -m clj-kond.main --lint src test script deps.edn bb.edn modules ops/exoscale/deploy")]
    (cond
      (= 2 exit) (status/die exit "clj-kondo found one or more lint errors")
      (= 3 exit) (status/die exit "clj-kondo found one or more lint warnings")
      (> exit 0) (status/die exit "clj-kondo returned unexpected exit code"))))

(def args-usage "Valid args: [options]

Options:
  --rebuild   Force rebuild of clj-kondo lint cache.
  --help      Show this help.")

(defn -main [& args]
  (when-let [opts (main/doc-arg-opt args-usage args)]
    (lint {:rebuild-cache (get opts "--rebuild")})))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

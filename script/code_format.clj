#!/usr/bin/env bb

(ns code-format
  (:require [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]))

(def args-usage "Valid args: [check|fix|--help]

Commands:
 check - reports on code formatting violations (default)
 fix   - fixes code formatting violations

Options
 --help        Show this help")

(defn -main [& args]
  (when-let [opts (main/doc-arg-opt args-usage args)]
    (let [cmd (or (some (fn [[k v]] (when v k)) opts) "check")]
      (status/line :head "%sing code format" cmd)
      (shell/command "clojure -M:code-format" cmd "src" "test" "modules" "script" "ops/exoscale/deploy"))))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

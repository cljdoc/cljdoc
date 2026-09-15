(ns code-format
  (:require [cljfmt.tool :as cljfmt]
            [lread.status-line :as status]))

(def paths ["src" "test" "modules" "script"
            "ops"
            "resources/migrations"
            "front-end/src"])

;; tasks

(defn check
  {:org.babashka/cli {:doc "reports on code formatting violations (default)"}}
  [_opts]
  (status/line :head "Checking code format")
  (cljfmt/check {:paths paths}))

(defn fix
  {:org.babashka/cli {:doc "fixes code formatting violations"}}
  [_opts]
  (status/line :head "Fixing code format")
  (cljfmt/fix {:paths paths}))

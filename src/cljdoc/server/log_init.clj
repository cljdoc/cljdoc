(ns cljdoc.server.log-init
  "Some libraries have unusual default logging, jetty for instance can emit DEBUG lines.
  We've separated out log-init to allow it to be easily required from other nses."
  (:require [unilog.config :as unilog]))

(set! *warn-on-reflection* true)

(def log-file (or (System/getenv "CLJDOC_LOG_FILE") "log/cljdoc.log"))

(unilog/start-logging!
 {:level   :info
  :console true
  :appenders [{:appender :rolling-file
               :file log-file
               :rolling-policy {:type :time-based
                                :max-history 14 ;; days (based on pattern)
                                :pattern "%d{yyyy-MM-dd}"}}]})

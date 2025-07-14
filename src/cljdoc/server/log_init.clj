(ns cljdoc.server.log-init
  "Some libraries have unusual default logging, jetty for instance can emit DEBUG lines.
  We've separated out log-init to allow it to be easily required from other nses."
  (:require [cljdoc.util.sentry :as sentry]
            [unilog.config :as unilog])
  (:import [ch.qos.logback.classic Level]
           [ch.qos.logback.classic.spi ILoggingEvent ThrowableProxy]
           [ch.qos.logback.core UnsynchronizedAppenderBase]))

(set! *warn-on-reflection* true)

(def log-file (or (System/getenv "CLJDOC_LOG_FILE") "log/cljdoc.log"))

(defn log-event->map
  "Convert log event for consumption by sentry"
  [^ILoggingEvent event]
  {:logger (.getLoggerName event)
   :log-message (.getFormattedMessage event)
   :log-timestamp (str (.getInstant event))
   :log-thread-name (.getThreadName event)
   ;; TODO: What to do when not a ThrowableProxy, does that happen?
   :log-exception (when-let [ex (.getThrowableProxy event)]
                    (when (instance? ThrowableProxy ex)
                      (.getThrowable ^ThrowableProxy ex)))})

(defmethod unilog/build-appender :sentry
  [config]
  (assoc config
         :appender
         (proxy [UnsynchronizedAppenderBase] []
           (start []
             (if-not (:sentry-dsn config)
               ;; TODO consider a custom error logger for errors when trying to talk to sentry
               ;; TODO if we dont' call `start` on super I expect this means appender is not started?
               (println "WARN: Sentry DSN not configured, errors will not be sent to sentry.io.")
               (let [^UnsynchronizedAppenderBase this this]
                 (proxy-super start))))
           (append [^ILoggingEvent event]
             (when (:sentry-dsn config)
               (try
                 (when (or (.isGreaterOrEqual (.getLevel event) Level/ERROR)
                           ;; tea-time logs errors at WARN, we consider them errors
                           (and (= "tea-time.core" (.getLoggerName event))
                                (.isGreaterOrEqual (.getLevel event) Level/WARN)))
                   (let [log-event (log-event->map event)]
                     (sentry/capture-log-event config log-event)))
                 (catch Throwable t
                   ;; TODO consider a custom error logger for errors when trying to talk to sentry
                   (println "ERROR: unable to send log event to sentry.io:" event t))))))))


(unilog/start-logging!
 {:level   :info
  :console true
  :appenders [(-> {:appender :sentry}
                  (merge sentry/config)
                  (assoc :log-min-level Level/WARN))
              {:appender :rolling-file
               :file "log/cljdoc.log"
               :rolling-policy {:type :time-based
                                :max-history 14 ;; days (based on pattern)
                                :pattern "%d{yyyy-MM-dd}"}}]})

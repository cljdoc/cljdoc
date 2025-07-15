(ns cljdoc.server.log.init
  "Some libraries have unusual default logging, jetty for instance can emit DEBUG lines.
  We've separated out log-init to allow it to be easily required from other nses."
  (:require [babashka.fs :as fs]
            [cljdoc.log.sentry :as sentry]
            [unilog.config :as unilog])
  (:import [ch.qos.logback.classic Level LoggerContext]
           [ch.qos.logback.classic.spi ILoggingEvent ThrowableProxy]
           [ch.qos.logback.core UnsynchronizedAppenderBase]
           [ch.qos.logback.core.status OnFileStatusListener StatusManager StatusListener]
           [ch.qos.logback.core.util StatusPrinter2]
           [java.io PrintStream]
           [org.slf4j LoggerFactory]))

(set! *warn-on-reflection* true)

(def log-file (str (fs/absolutize (or (System/getenv "CLJDOC_LOG_FILE") "log/cljdoc.log"))))
(def log-dir (str (fs/parent log-file)) )
(def logger-file (str (fs/path log-dir "logger.log")))
(fs/create-dirs log-dir)

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
               (let [^UnsynchronizedAppenderBase this this]
                 (proxy-super addWarn "Sentry DSN not configured, logged errors will not be sent to sentry.io. This is normal for local dev."))
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
                   (let [^UnsynchronizedAppenderBase this this]
                     (proxy-super addError (str "Unable to forward log event event to sentry.io:" event) t)))))))))

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

;; register a status listener so that we can see "status" errors from our appender
(let [^LoggerContext lc (LoggerFactory/getILoggerFactory)
      ^StatusManager status-manager (.getStatusManager lc)
      ^StatusListener listener (doto (OnFileStatusListener.)
                         (.setFilename logger-file))]
  (.add status-manager listener)
  ;; Print any pre-existing status messages
  (.print (doto (StatusPrinter2.)
            (.setPrintStream (PrintStream. (fs/file logger-file)))) status-manager))

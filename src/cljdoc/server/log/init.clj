(ns cljdoc.server.log.init
  "Some libraries have unusual default logging, jetty for instance can emit DEBUG lines.
  We've separated out log init to allow it to be easily required from other nses."
  (:require [babashka.fs :as fs]
            [cljdoc.server.log.sentry :as sentry])
  (:import [ch.qos.logback.classic Level LoggerContext Logger]
           [ch.qos.logback.classic.encoder PatternLayoutEncoder ]
           [ch.qos.logback.classic.spi ILoggingEvent ThrowableProxy Configurator Configurator$ExecutionStatus]
           [ch.qos.logback.core Appender UnsynchronizedAppenderBase ConsoleAppender OutputStreamAppender ]
           [ch.qos.logback.core.encoder Encoder]
           [ch.qos.logback.core.rolling RollingFileAppender RollingPolicy TimeBasedRollingPolicy]
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

#_(defmethod unilog/build-appender :sentry
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


#_(defn sentry-appender [config]
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
                     (proxy-super addError (str "Unable to forward log event event to sentry.io:" event) t))))))))

(defn- add-status-manager
  "Tell logback to info/warn/errors from logging to a file."
  [^LoggerContext ctx]
  (println "adding status manager")
  (let [^StatusManager status-manager (.getStatusManager ctx)
        ^StatusListener listener (doto (OnFileStatusListener.)
                                   (.setContext ctx)
                                   ;; TODO: Consider maybe timestamping? Deleting old?
                                   (.setFilename logger-file)
                                   .start)]
    (.add status-manager listener)))

(defn- add-pattern-encoder ^Encoder [ctx]
  (let [^PatternLayoutEncoder encoder (PatternLayoutEncoder.)]
      (.setContext encoder ctx)
      (.setPattern encoder "[%level] %msg%n")
      (.start encoder)
      encoder))

(defn- add-console-appender ^OutputStreamAppender [ctx ^Encoder encoder]
  (let [^OutputStreamAppender appender (ConsoleAppender.)]
      (.setContext appender ctx)
      (.setName appender "console")
      (.setEncoder appender encoder)
      (.start appender)
      appender))

(defn- rolling-log-filename [log-file]
  (let [path (fs/parent log-file)
        file-name (fs/file-name log-file)
        [base-name ext] (fs/split-ext file-name)]
    (str (fs/path path (str base-name "-%d{yyyy-MM-dd}" "." ext)))))

(defn- add-rolling-file-appender ^OutputStreamAppender [ctx ^Encoder encoder]
  (let [^RollingFileAppender appender (RollingFileAppender.)]
      (.setContext appender ctx)
      (.setFile appender log-file)
      (.setRollingPolicy appender (doto (TimeBasedRollingPolicy.)
                                    (.setContext ctx)
                                    (.setMaxHistory 14) ;; max files to keep
                                    (.setFileNamePattern (rolling-log-filename log-file))
                                    (.setParent appender)
                                    (.start)))
      (.setName appender "rolling-file")
      (.setEncoder appender encoder)
      (.start appender)
      appender))

(defn configure [^LoggerContext ctx]
  (println "hello from clojure configurator" ctx)
  (add-status-manager ctx)
  (let [encoder (add-pattern-encoder ctx)
        console-appender (add-console-appender ctx encoder)
        rolling-file-appender (add-rolling-file-appender ctx encoder)
        ^Logger root-logger (.getLogger ctx Logger/ROOT_LOGGER_NAME)]
    (.setLevel root-logger Level/INFO)
    (.addAppender root-logger console-appender)
    (.addAppender root-logger rolling-file-appender)))

#_(unilog/start-logging!
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


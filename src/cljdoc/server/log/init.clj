(ns cljdoc.server.log.init
  "Initialize our logging via [[configure]] which is called by LogConfigurator.java
  which is invoked as a service. Using a logback Configurator has 3 advantages:
  1. Logging is initialized very early for us. We don't need to worry about it.
  2. We get full access to features of logback (as opposed to using an abstraction like unilog which only
     exposes a subset of features).
  3. We can access and inject any config easily and programmatically. I prefer this over
     less convenient mechanism available in a logback.xml file, for example."
  (:require [babashka.fs :as fs]
            [cljdoc.config :as cfg]
            [cljdoc.server.log.sentry :as sentry])
  (:import [ch.qos.logback.classic Level Logger LoggerContext]
           [ch.qos.logback.classic.encoder PatternLayoutEncoder]
           [ch.qos.logback.classic.spi ILoggingEvent ThrowableProxy]
           [ch.qos.logback.core Appender ConsoleAppender OutputStreamAppender UnsynchronizedAppenderBase]
           [ch.qos.logback.core.encoder Encoder]
           [ch.qos.logback.core.rolling RollingFileAppender TimeBasedRollingPolicy]
           [ch.qos.logback.core.status  StatusListener StatusManager WarnStatus]
           [cljdoc.server.log CustomFileStatusListener]
           [java.time LocalDate]))

(set! *warn-on-reflection* true)

(def log-file (str (fs/absolutize (or (System/getenv "CLJDOC_LOG_FILE") "log/cljdoc.log"))))
(def ^:private log-dir (str (fs/parent log-file)))
(def ^:private logger-file (str (fs/path log-dir (str "logger.started-" (.toString (LocalDate/now)) ".log"))))

(fs/create-dirs log-dir)

(defn- log-event->map
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

(defn- sentry-appender [config]
  (proxy [UnsynchronizedAppenderBase] []
    (start []
      (let [^UnsynchronizedAppenderBase this this]
        (proxy-super start)))
    (append [^ILoggingEvent event]
      (when (:sentry-dsn config)
        (try
          ;; TODO Probably more idiomatic to specify as a logback filter?...
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
  ^StatusManager [^LoggerContext ctx logger-file]
  (let [^StatusManager status-manager (.getStatusManager ctx)
        ^StatusListener listener (doto (CustomFileStatusListener.)
                                   (.setContext ctx)
                                   (.setFilename logger-file)
                                   .start)]
    (.add status-manager listener)
    status-manager))

(defn- add-pattern-encoder ^Encoder [ctx log-pattern]
  (let [^PatternLayoutEncoder encoder (PatternLayoutEncoder.)]
    (.setContext encoder ctx)
    (.setPattern encoder log-pattern)
    (.start encoder)
    encoder))

(defn- add-console-appender ^OutputStreamAppender [ctx ^Encoder encoder]
  (let [^OutputStreamAppender appender (ConsoleAppender.)]
    (.setContext appender ctx)
    (.setName appender "console")
    (.setEncoder appender encoder)
    (.start appender)
    appender))

(defn- rolling-log-filename [log-file log-file-pattern]
  (let [path (fs/parent log-file)
        file-name (fs/file-name log-file)
        [base-name ext] (fs/split-ext file-name)]
    (str (fs/path path (str base-name log-file-pattern "." ext)))))

(defn- add-rolling-file-appender ^OutputStreamAppender [ctx log-file log-file-pattern ^Encoder encoder]
  (let [^RollingFileAppender appender (RollingFileAppender.)]
    (.setContext appender ctx)
    (.setName appender "rolling-file")
    (.setFile appender log-file)
    (.setRollingPolicy appender (doto (TimeBasedRollingPolicy.)
                                  (.setContext ctx)
                                  (.setMaxHistory 14) ;; max files to keep
                                  (.setFileNamePattern (rolling-log-filename log-file log-file-pattern))
                                  (.setParent appender)
                                  (.start)))
    (.setEncoder appender encoder)
    (.start appender)
    appender))

(defn- add-sentry-appender ^OutputStreamAppender [ctx sentry-config]
  (let [^Appender appender (sentry-appender sentry-config)]
    (.setContext appender ctx)
    (.setName appender "sentry")
    (.start appender)
    appender))

(defn configure
  "Invoked by cljdoc.sever.log.LogConfigurator (java)"
  [^LoggerContext ctx]
  (let [status-manager (add-status-manager ctx logger-file)
        encoder (add-pattern-encoder ctx "%p [%d] %t - %c %m%n")
        ^Logger root-logger (.getLogger ctx Logger/ROOT_LOGGER_NAME)]
    (.setLevel root-logger Level/INFO)
    (.addAppender root-logger (add-console-appender ctx encoder))
    (.addAppender root-logger (add-rolling-file-appender ctx log-file "-%d{yyyy-MM-dd}" encoder))
    (if-not (cfg/get-in (cfg/config) [:cljdoc/server :enable-sentry?])
      (.add status-manager (WarnStatus. "Sentry DSN not configured, sentry appender not started, logged errors will not be sent to sentry.io. This is normal for local dev."
                                        ctx))
      (.addAppender root-logger (add-sentry-appender ctx sentry/config)))))

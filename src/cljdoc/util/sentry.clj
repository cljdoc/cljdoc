(ns cljdoc.util.sentry
  (:require [cljdoc.config :as cfg]
            [unilog.config :as unilog]
            [raven-clj.core :as raven]
            [raven-clj.interfaces :as interfaces]
            [clojure.tools.logging :as log]
            [io.pedestal.interceptor :as interceptor])
  (:import (io.sentry Sentry)
           (io.sentry.logback SentryAppender)
           (ch.qos.logback.core.spi FilterReply)
           (ch.qos.logback.classic.filter LevelFilter)
           (ch.qos.logback.classic Level)))

(def app-namespaces
  ["cljdoc"])

(defmethod unilog/build-appender :sentry
  [config]
  (let [f (doto (LevelFilter.)
            (.setLevel Level/WARN)
            (.setOnMatch FilterReply/ACCEPT)
            (.setOnMismatch FilterReply/DENY)
            (.start))]
    (assoc config :appender (doto (SentryAppender.)
                              (.addFilter f)))))

(when (cfg/sentry-dsn)
  (log/info "Sentry DSN found, installing uncaught exception handler")
  (Sentry/init (str (cfg/sentry-dsn) "?" "stacktrace.app.packages=" (first app-namespaces)))
  (raven/install-uncaught-exception-handler!
   (cfg/sentry-dsn)
   {:packet-transform (fn [packet] (assoc packet :release (cfg/version)))
    :app-namespaces app-namespaces
    :handler (fn [thread ex] (log/errorf ex "Uncaught exception on thread %s" (.getName thread)))}))

(defn capture [{:keys [req ex]}]
  (if (cfg/sentry-dsn)
    (let [payload (cond-> {:release (cfg/version)}
                    ex  (interfaces/stacktrace ex app-namespaces)
                    req (interfaces/http req identity))
          sentry-response (raven/capture (cfg/sentry-dsn) payload)]
      (when-not (= 200 (:status sentry-response))
        (log/errorf "Failed to log error to Sentry %s %s"
                    (:status sentry-response) (:body sentry-response))))
    (log/warn "DSN missing: Not tracking error in Sentry")))

(def interceptor
  (interceptor/interceptor
   {:name ::interceptor
    :error (fn sentry-intercept [ctx ex-info]
             (log/error ex-info
                        "Exception when processing request"
                        (merge (dissoc (ex-data ex-info) :exception)
                               {:path-params (-> ctx :request :path-params)
                                :route-name  (-> ctx :route :route-name)}))
             (capture {:ex ex-info :req (:request ctx)})
             (assoc ctx :response {:status 500 :body "An exception occurred, sorry about that!"}))}))

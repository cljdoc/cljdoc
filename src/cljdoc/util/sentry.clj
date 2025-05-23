(ns cljdoc.util.sentry
  (:require [cljdoc.config :as cfg]
            [clojure.tools.logging :as log]
            [io.pedestal.interceptor :as interceptor]
            [raven-clj.core :as raven]
            [raven-clj.interfaces :as interfaces]))

(def app-namespaces
  ["cljdoc"])

(when (cfg/sentry-dsn)
  (log/info "Sentry DSN found, installing uncaught exception handler")
  (raven/install-uncaught-exception-handler!
   (cfg/sentry-dsn)
   {:packet-transform (fn [packet] (assoc packet :release (cfg/version)))
    :app-namespaces app-namespaces
    :handler (fn [^Thread thread ex] (log/errorf ex "Uncaught exception on thread %s" (.getName thread)))}))

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

(comment
  (capture {:ex (ex-info "lee testing 1234" {:moo :dog})})

  :eoc)

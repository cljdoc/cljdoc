(ns cljdoc.util.sentry
  (:require [cljdoc.config :as cfg]
            [raven-clj.core :as raven]
            [raven-clj.interfaces :as interfaces]
            [clojure.tools.logging :as log]))

(def app-namespaces
  ["cljdoc"])

(if (cfg/sentry-dsn)
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
  {:name  ::interceptor
   :error (fn sentry-intercept [ctx ex-info]
            (capture {:ex ex-info :req (:request ctx)})
            (throw ex-info))})

(comment
  (raven/parse-dsn dsn)

  (capture {:ex cljdoc.server.pedestal/-error-ex
            :req (:request cljdoc.server.pedestal/-error-ctx)})


  (clojure.pprint/pprint
   (-> {}
       (interfaces/stacktrace cljdoc.server.pedestal/-error-ex)
       (interfaces/http (:request cljdoc.server.pedestal/-error-ctx)
                        identity)))

  (identity cljdoc.server.pedestal/-error-ex)

  )



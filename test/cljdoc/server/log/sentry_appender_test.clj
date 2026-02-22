(ns cljdoc.server.log.sentry-appender-test
  "Does our sentry appender and custom logger log work as expected?"
  (:require [babashka.fs :as fs]
            [cljdoc.server.log.init :as log-init]
            [cljdoc.server.log.sentry :as sentry]
            [clojure.string :as str]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test])
  (:import [ch.qos.logback.classic LoggerContext]
           [ch.qos.logback.classic.util LogbackMDCAdapter]
           [java.time Clock]))

(defn- logging-scenario [{:keys [logger-file] :as config}]
  (fs/create-dirs (fs/parent logger-file))
  (fs/delete-if-exists logger-file)
  (let [ctx (LoggerContext.)]
    (log-init/configure* ctx config)
    ;; a little hack to initialize something that gets initialized automatically in production
    (.setMDCAdapter ctx (LogbackMDCAdapter.))
    (.start ctx)
    ctx))

(defn- status-list [ctx]
  (->> ctx
       .getStatusManager
       .getCopyOfStatusList
       (into [])))

(defn- status-list-clear [ctx]
  (->> ctx
       .getStatusManager
       .clear))

(defn- logger-file [id]
  (str (fs/file "target" "log-test" (str id ".log"))))

(def sentry-test-config
  {:logger-file nil ;; set in tests
   :enable-sentry? true
   :sentry-dsn "https://some-dsn-url"
   :sentry-project-id 123456789
   :environment "some-env"
   :release "some-guid"
   :sentry-client {:name "cljdoc.clojure"
                   :version "0.0.1"}
   :server-name "some-server"
   :app-namespaces ["cljdoc"]
   :clock (atom (Clock/systemUTC))})

(t/deftest no-log-events-appended-if-sentry-not-configured-test
  ;; when there is no :sentry-dsn in config we do not log events
  (let [payloads-sent (atom [])
        ctx (logging-scenario
             {:logger-file (logger-file "no-log-events-appended")
              :enable-sentry? false
              :build-sentry-payload-fn sentry/build-sentry-payload
              :submit-sentry-payload-fn (fn [_config payload]
                                          (swap! payloads-sent conj payload))})]

    (try
      (.error (.getLogger ctx "cljdoc.boop") "something bad happened")
      (t/is (match? [] @payloads-sent))
      (t/is (match? [#"Sentry DSN not configured"]
                    (->> (status-list ctx)
                         (remove #(str/starts-with? (str %) "INFO")) ;; ignore startup info msgs
                         (map #(.getMessage %))))
            "no logging errors")
      (finally
        (.stop ctx)))))

(t/deftest error-log-events-appended-test
  (let [payloads-sent (atom [])
        ctx (logging-scenario
             (assoc sentry-test-config
                    :logger-file (logger-file "error-log-events-appended")
                    :build-sentry-payload-fn sentry/build-sentry-payload
                    :submit-sentry-payload-fn (fn [_config payload]
                                                (swap! payloads-sent conj payload))))]
    (status-list-clear ctx)
    (try
      (.debug (.getLogger ctx "cljdoc.boop") "debug message")
      (.warn (.getLogger ctx "cljdoc.boop") "warn message")
      (.info (.getLogger ctx "cljdoc.boop") "info message")
      (.error (.getLogger ctx "cljdoc.boop") "something bad happened")
      (.error (.getLogger ctx "cljdoc.boop") "and another thing")
      (.warn (.getLogger ctx "tea-time.core") "tea time warn is special") ;; we consider these errors
      (.error (.getLogger ctx "tea-time.core") "tea time error is error")
      (.info (.getLogger ctx "tea-time.core") "tea time info is ignored")
      (t/is (match? [#"something bad happened"
                     #"and another thing"
                     #"tea time warn is special"
                     #"tea time error is error"]
                    @payloads-sent))
      (t/is (match? [] (status-list ctx)) "no logging errors")
      (finally
        (.stop ctx)))))

(t/deftest log-to-loggerlog-on-build-payload-error-test
  (let [payloads-sent (atom [])
        ctx (logging-scenario
             (assoc sentry-test-config
                    :logger-file (logger-file "log-to-loggerlog-on-build-payload-error")
                    :build-sentry-payload-fn (fn [_config _log-event]
                                               (throw (ex-info "PAYLOAD BUILD PROBLEM" {})))
                    :submit-sentry-payload-fn (fn [_config payload]
                                                (swap! payloads-sent conj payload))))]
    (status-list-clear ctx)
    (try
      (.debug (.getLogger ctx "cljdoc.boop") "debug message ignored")
      (.warn (.getLogger ctx "cljdoc.boop") "warn message ignored")
      (.info (.getLogger ctx "cljdoc.boop") "info message ignored")
      (.error (.getLogger ctx "cljdoc.boop") "error not ignored")
      (t/is (match? [] @payloads-sent) "could not send any payloads")
      (t/is (match? [{:message #"Failed to append event to sentry: .*error not ignored"
                      :throwable {:via [{:message #"Unable to create sentry payload from log-event: \{.*:log-message \"error not ignored\""}
                                        {:message "PAYLOAD BUILD PROBLEM"}]}}]
                    (map (fn [s]
                           {:message (.getMessage s)
                            :throwable (Throwable->map (.getThrowable s))})
                         (status-list ctx))) "no logging errors")
      (finally
        (.stop ctx)))))

(t/deftest log-to-loggerlog-on-submit-payload-error-test
  (let [payloads-sent (atom [])
        ctx (logging-scenario
             (assoc sentry-test-config
                    :logger-file (logger-file "log-to-loggerlog-on-submit-payload-error")
                    :build-sentry-payload-fn sentry/build-sentry-payload
                    :submit-sentry-payload-fn (fn [_config _payload]
                                                (throw (ex-info "PAYLOAD SUBMIT PROBLEM" {})))))]
    (status-list-clear ctx)
    (try
      (.debug (.getLogger ctx "cljdoc.boop") "debug message ignored")
      (.warn (.getLogger ctx "cljdoc.boop") "warn message ignored")
      (.info (.getLogger ctx "cljdoc.boop") "info message ignored")
      (.error (.getLogger ctx "cljdoc.boop") "error not ignored")
      (t/is (match? [] @payloads-sent) "could not send any payloads")
      (t/is (match? [{:message #"Failed to append event to sentry: .*error not ignored"
                      :throwable {:via [{:message #"Unable to send event payload to sentry.io: \{.*\"dsn\":\"SENTRY_DSN_OCCLUDED\""}
                                        {:message "PAYLOAD SUBMIT PROBLEM"}]}}]
                    (map (fn [s]
                           {:message (.getMessage s)
                            :throwable (Throwable->map (.getThrowable s))})
                         (status-list ctx))) "no logging errors")
      (finally
        (.stop ctx)))))

(t/deftest log-to-loggerlog-nested-error-test
  ;; we'll actually do some spot checks on our logger-file for this one
  (let [payloads-sent (atom [])
        logger-file (logger-file "log-to-loggerlog-nested-error")
        ctx (logging-scenario
             (assoc sentry-test-config
                    :logger-file logger-file
                    :build-sentry-payload-fn sentry/build-sentry-payload
                    :submit-sentry-payload-fn (fn [_config _payload]
                                                (throw (ex-info "PAYLOAD SUBMIT PROBLEM" {}
                                                                (ex-info "DUE TO SOME ERROR" {}
                                                                         (ex-info "ROOT CAUSE" {})))))))]
    (status-list-clear ctx)
    (try
      (.debug (.getLogger ctx "cljdoc.boop") "debug message ignored")
      (.warn (.getLogger ctx "cljdoc.boop") "warn message ignored")
      (.info (.getLogger ctx "cljdoc.boop") "info message ignored")
      (.error (.getLogger ctx "cljdoc.boop") "error not ignored")
      (t/is (match? [] @payloads-sent) "could not send any payloads")
      (t/is (match? [{:message #"Failed to append event to sentry: .*error not ignored"
                      :throwable {:via [{:message #"Unable to send event payload to sentry.io: \{.*\"dsn\":\"SENTRY_DSN_OCCLUDED\""}
                                        {:message "PAYLOAD SUBMIT PROBLEM"}
                                        {:message "DUE TO SOME ERROR"}
                                        {:message "ROOT CAUSE"}]
                                  :cause "ROOT CAUSE"}}]
                    (map (fn [s]
                           {:message (.getMessage s)
                            :throwable (Throwable->map (.getThrowable s))})
                         (status-list ctx))) "no logging errors")
      (let [logger-lines (->> (slurp logger-file)
                              str/split-lines)]
        (t/is (= 1 (count (filter
                           #(re-find #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \|-ERROR" %)
                           logger-lines)))
              "one error logged")
        (t/is (match? (m/embeds [#"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \|-ERROR .* Failed to append"
                                 #"Unable to send event payload to sentry\.io"
                                 #"Caused by: .* PAYLOAD SUBMIT PROBLEM"
                                 #"Caused by: .* DUE TO SOME ERROR"
                                 #"Caused by: .* ROOT CAUSE"])
                      logger-lines)))
      (finally
        (.stop ctx)))))

(comment
  (let [logger-file (logger-file "log-to-loggerlog-nested-error")
        logger-lines (->> (slurp logger-file)
                          str/split-lines)]
    (filter
      #(re-find #"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} -\|-ERROR .*" %)
      logger-lines))

  (re-find #"\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} \|-ERROR"
           "2026-02-22 12:33:15.404 |-ERROR in cljdoc.server.log")




  :eoc)

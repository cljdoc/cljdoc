(ns cljdoc.server.log.sentry-test
  "Inspired by, and lifted from, raven-clj tests"
  (:require [cheshire.core :as json]
            [cljdoc.server.log.sentry :as sentry]
            [cljdoc.test.clock :as clock]
            [clojure.string :as str]
            [clojure.test :as t]
            [lambdaisland.uri :as uri]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test])
  (:import [java.time Clock]))

(defn- make-dsn
  [opts]
  (str
   (merge (uri/uri "https://example.com")
          {:user "b70a31b3510c4cf793964a185cfe1fd0"
           :password "b7d80b520139450f903720eb7991bf3d"}
          opts)))

(t/deftest extract-project-id-test
  (doseq [[desc expected-project-id dsn]
          [["simple case"        1           (make-dsn {:path "/1"})]
           ["project id is long" 99999999999 (make-dsn {:path "/99999999999"})]
           ["no secret"          1           (make-dsn {:path "/1" :password nil})]
           ["path prefix"        1           (make-dsn {:path "/sentry/1"})]
           ["port"               1           (make-dsn {:path "/1" :port 9000})]
           ["port & path prefix" 1           (make-dsn {:path "/sentry/1" :port 9000})]
           ["query string"       1           (make-dsn {:path "/sentry/1" :port 9000 :query "environment=test&servername=example"})]]]
    (t/is (= expected-project-id (sentry/extract-project-id dsn)) (str desc ": " dsn))))

(def test-config
  {:sentry-dsn "https://some-dsn-url"
   :sentry-project-id 123456789
   :environment "some-env"
   :release "some-guid"
   :sentry-client {:name "cljdoc.clojure"
                   :version "0.0.1"}
   :server-name "some-server"
   :timeout-ms 3000
   :app-namespaces ["cljdoc"]
   :clock (atom (Clock/systemUTC))})

(def sample-source       "cljdoc/server/log/sentry/sample_source.clj")
(def sample-source-small "cljdoc/server/log/sentry/sample_source_small.clj")

(t/deftest frame->sentry-context-test
  (doseq [[desc file-path line-number expected-pre-context expected-context-line expected-post-context]
          [["context mid file"
            sample-source
            10
            [";; line 5" ";; line 6" ";; line 7" ";; line 8" ";; line 9"]
            ";; line 10"
            [";; line 11" ";; line 12" ";; line 13" ";; line 14" ";; line 15"]]
           ["context first lines of file"
            sample-source
            1
            []
            "(ns cljdoc.server.log.sentry.sample-source)"
            [";; line 2" ";; line 3" ";; line 4" ";; line 5" ";; line 6"]]
           ["context last lines of file"
            sample-source
            15
            [";; line 10" ";; line 11" ";; line 12" ";; line 13" ";; line 14"]
            ";; line 15"
            [";; line 16" ";; line 17" ";; line 18" ";; line 19" ";; line 20"]]
           ["target line reduces pre-context"
            sample-source
            3
            ["(ns cljdoc.server.log.sentry.sample-source)" ";; line 2"]
            ";; line 3"
            [";; line 4" ";; line 5" ";; line 6" ";; line 7" ";; line 8"]]
           ["target line reduces post-context"
            sample-source
            18
            [";; line 13" ";; line 14" ";; line 15" ";; line 16" ";; line 17"]
            ";; line 18"
            [";; line 19" ";; line 20"]]
           ["target line eliminates post-context"
            sample-source
            20
            [";; line 15" ";; line 16" ";; line 17" ";; line 18" ";; line 19"]
            ";; line 20"
            []]
           ["target line out of bounds zero"
            sample-source
            0]
           ["target line out of bounds negative"
            sample-source
            -1]
           ["target line out of bounds upper"
            sample-source
            21]
           ;; file missing
           ["file is missing"
            "src/wont_find_me_anywhere.clj"
            3]
           ;; small file
           ["small file 0"
            sample-source-small
            0]
           ["small file 1"
            sample-source-small
            1
            []
            "(ns cljdoc.server.log.sentry.sample-source-small)"
            [";; line 2" ";; line 3" ";; line 4"]]
           ["small file 2"
            sample-source-small
            2
            ["(ns cljdoc.server.log.sentry.sample-source-small)"]
            ";; line 2"
            [";; line 3" ";; line 4"]]
           ["small file 3"
            sample-source-small
            3
            ["(ns cljdoc.server.log.sentry.sample-source-small)" ";; line 2"]
            ";; line 3"
            [";; line 4"]]
           ["small file 4"
            sample-source-small
            4
            ["(ns cljdoc.server.log.sentry.sample-source-small)" ";; line 2" ";; line 3"]
            ";; line 4"
            []]
           ["small file 5"
            sample-source-small
            5]]]
    (t/is (match? (m/nested-equals (cond-> {:filename "filename.clj"
                                            :lineno line-number
                                            :function "cljdoc.server.log.sentry/baz"
                                            :in_app true}
                                     expected-pre-context (assoc :pre_context expected-pre-context)
                                     expected-context-line (assoc :context_line expected-context-line)
                                     expected-post-context (assoc :post_context expected-post-context)))
                  (sentry/frame->sentry {:class-path-url file-path
                                         :file-name "filename.clj"
                                         :line-number line-number
                                         :package "cljdoc.server.log.sentry"
                                         :method-name "baz"}
                                        {:app-namespaces ["cljdoc"]}))
          desc)))

(t/deftest frame->sentry-not-in-app
  (t/is (match? {:filename "core.clj"
                 :lineno 44
                 :function "clojure/baz"
                 :in_app false}
                (sentry/frame->sentry {:class-path-url "clojure/core.clj"
                                       :file-name "core.clj"
                                       :line-number 44
                                       :package "clojure"
                                       :method-name "baz"}
                                      {:app-namespaces ["cljdoc"]}))))

(def expected-header {:dsn (:sentry-dsn test-config)
                      :sdk (:sentry-client test-config)
                      :sent_at :tbd})

(def expected-item {:type "event"
                    :content_type "application/json"
                    :length :tbd})

(def uuid-pattern #"^\p{XDigit}{8}-(\p{XDigit}{4}-){3}\p{XDigit}{12}$")

(def test-event {:logger "some.logger"
                 :log-timestamp "2025-07-01T11:12:13.123Z"
                 :log-message "Something bad happened"
                 :log-thread-name "some-thread"})

(def expected-payload {:environment "some-env"
                       :event_id uuid-pattern
                       :level "error"
                       :logentry {:formatted (:log-message test-event)}
                       :logger (:logger test-event)
                       :platform "clojure"
                       :release (:release test-config)
                       :sdk (:sentry-client test-config)
                       :server_name (:server-name test-config)
                       :timestamp (:log-timestamp test-event)})

(t/deftest build-envelope-test
  (t/is (match? (m/nested-equals [expected-header
                                  expected-item
                                  expected-payload])
                (sentry/build-envelope {:config test-config
                                        :log-event test-event}))))

(t/deftest build-envelope-with-exception-test
  (t/is (match? [expected-header
                 expected-item
                 (assoc expected-payload :exception {:values [{:type "clojure.lang.ExceptionInfo"
                                                               :value "something bad happened"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "cljdoc-sentry-appender"
                                                                           :exception_id 0
                                                                           :data {":some" ":data"}}}]})]
                (sentry/build-envelope {:config test-config
                                        :log-event (assoc test-event
                                                          :log-exception (ex-info "something bad happened" {:some :data}))}))))

(t/deftest build-envelope-with-chained-exception-test
  (t/is (match? [expected-header
                 expected-item
                 (assoc expected-payload :exception {:values [{:type "clojure.lang.ExceptionInfo"
                                                               :value "bad1"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "cljdoc-sentry-appender"
                                                                           :exception_id 3
                                                                           :data {":bad1" ":data1"}}}
                                                              {:type "clojure.lang.ExceptionInfo"
                                                               :value "bad2"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "chained"
                                                                           :exception_id 2
                                                                           :data {}}}
                                                              {:type "clojure.lang.ExceptionInfo"
                                                               :value "bad3"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "chained"
                                                                           :exception_id 1
                                                                           :data {":bad3" ":data3"}}}
                                                              {:type "clojure.lang.ExceptionInfo"
                                                               :value "cause"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "chained"
                                                                           :exception_id 0
                                                                           :data {":cause" ":cause-data"}}}]})]
                (sentry/build-envelope {:config test-config
                                        :log-event (assoc test-event
                                                          :log-exception (ex-info "bad1" {:bad1 :data1}
                                                                                  (ex-info "bad2" {}
                                                                                           (ex-info "bad3" {:bad3 :data3}
                                                                                                    (ex-info "cause" {:cause :cause-data})))))}))))

(t/deftest build-sentry-payload-test
  (let [fake-now "2026-02-21T23:24:25.261234Z"
        result (sentry/build-sentry-payload (assoc test-config :clock (clock/fake-clock fake-now))
                                            (assoc test-event
                                                   :log-exception (ex-info "something bad happened" {:some :data})))
        [header item payload] (str/split-lines result)
        ;; length can vary due to exception traces
        expected-payload-length (alength (.getBytes payload "UTF-8"))]
    (t/is (match? (m/equals
                   {"dsn" "https://some-dsn-url"
                    "sdk" {"name" "cljdoc.clojure"
                           "version" "0.0.1"}
                    "sent_at" fake-now})
                  (json/decode header)) "header")
    (t/is (match? (m/equals
                   {"type" "event"
                    "content_type" "application/json"
                    "length" expected-payload-length})
                  (json/decode item)) "item")
    (t/is (match? {"sdk" {"name" "cljdoc.clojure" "version" "0.0.1"}
                   "release" "some-guid"
                   "event_id" uuid-pattern
                   "level" "error"
                   "logger" "some.logger"
                   "environment" "some-env"
                   "server_name" "some-server"
                   "timestamp" "2025-07-01T11:12:13.123Z"
                   "logentry" {"formatted" "Something bad happened"}
                   "platform" "clojure"
                   ;; skip exception testing, assume covered in tests above
                   }
                  (json/decode payload)) "payload")))

(t/deftest occlude-sensitive-test
  (let [payload (sentry/build-sentry-payload test-config
                                             (assoc test-event
                                                    :log-exception (ex-info "something bad happened" {:some :data})))
        occluded (sentry/occlude-sensitive test-config payload)]
    (t/is (not= payload occluded) "occluded payload is not the same as payload")
    (t/is (not (str/includes? payload (str (:sentry-project-id test-config)))) "original payload does not include project id")
    (t/is (str/includes? payload (:sentry-dsn test-config)) "original payload includes dsn")
    (t/is (not (str/includes? occluded (str (:sentry-project-id test-config)))) "occluded payload does not include project id")
    (t/is (not (str/includes? occluded (:sentry-dsn test-config))) "occluded payload does not include dsn")
    (t/is (str/includes? occluded "SENTRY_DSN_OCCLUDED") "dsn is occluded")))

(comment

  (java.time.Instant/now)

  (make-dsn {:path "/1"})
  ;; => "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com/1"

  (make-dsn {:path "/sentry/1" :password nil :query "environment=test&servername=example"})
  ;; => "https://b70a31b3510c4cf793964a185cfe1fd0@example.com/sentry/1?environment=test&servername=example"

  (println "foo")

  :eoc)

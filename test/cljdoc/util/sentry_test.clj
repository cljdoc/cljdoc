(ns cljdoc.util.sentry-test
  "Inspired by, and lifted from, raven-clj tests"
  (:require [cljdoc.util.sentry :as sentry]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

(t/deftest extract-project-id-test
  (t/testing "dsn parsing"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com/1"))))

  (t/testing "dsn parsing with long"
    (t/is (= 99999999999
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com/99999999999"))))

  (t/testing "dsn parsing without secret"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0@example.com/1"))))

  (t/testing "dsn parsing with path"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com/sentry/1"))))

  (t/testing "dsn parsing with port"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com:9000/1"))))

  (t/testing "dsn parsing with port and path"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com:9000/sentry/1"))))

  (t/testing "dsn parsing with query parameters"
    (t/is (= 1
             (sentry/extract-project-id
              "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com:9000/sentry/1?environment=test&servername=example")))))

(def test-config
  "We initalize at load time rather than via integrant because we need logging to be up as early as possible"
  {:sentry-dsn "https://some-dsn-url"
   :sentry-project-id 1
   :environment "some-env"
   :release "some-guid"
   :sentry-client {:name "cljdoc.clojure"
                   :version "0.0.1"}
   :server-name "some-server"
   :timeout-ms 3000
   :app-namespaces ["cljdoc"]})

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
                 :log-thread-name "some-thread"
                 #_#_:log-exception (ex-info "something bad happened" {:some :data})})

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

(t/deftest frame->sentry-context-test
  (doseq [[desc line-number expected-pre-context expected-context-line expected-post-context]
          [["context mid file"
            10
            [";; line 5" ";; line 6" ";; line 7" ";; line 8" ";; line 9"]
            ";; line 10"
            [";; line 11" ";; line 12" ";; line 13" ";; line 14" ";; line 15"]]
           ["context first lines of file"
            1
            []
            "(ns cljdoc.util.sentry.sample-source)"
            [";; line 2" ";; line 3" ";; line 4" ";; line 5" ";; line 6"]]
           ["context last lines of file"
            15
            [";; line 10" ";; line 11" ";; line 12" ";; line 13" ";; line 14"]
            ";; line 15"
            [";; line 16" ";; line 17" ";; line 18" ";; line 19" ";; line 20"]]
           ["target line reduces pre-context"
            3
            ["(ns cljdoc.util.sentry.sample-source)" ";; line 2"]
            ";; line 3"
            [";; line 4" ";; line 5" ";; line 6" ";; line 7" ";; line 8"]]
           ["target line reduces post-context"
            18
            [";; line 13" ";; line 14" ";; line 15" ";; line 16" ";; line 17"]
            ";; line 18"
            [";; line 19" ";; line 20"]]
           ["target line eliminates post-context"
            20
            [";; line 15" ";; line 16" ";; line 17" ";; line 18" ";; line 19"]
            ";; line 20"
            []]
           ["target line out of bounds zero" 0]
           ["target line out of bounds negative" -1]
           ["target line out of bounds upper" 21]]]
    (t/is (match? (m/nested-equals (cond-> {:filename "filename.clj"
                                            :lineno line-number
                                            :function "cljdoc.util.sentry/baz"
                                            :in_app true}
                                     expected-pre-context (assoc :pre_context expected-pre-context)
                                     expected-context-line (assoc :context_line expected-context-line)
                                     expected-post-context (assoc :post_context expected-post-context)))
                  (sentry/frame->sentry {:class-path-url "cljdoc/util/sentry/sample_source.clj"
                                         :file-name "filename.clj"
                                         :line-number line-number
                                         :package "cljdoc.util.sentry"
                                         :method-name "baz"}
                                        {:app-namespaces ["cljdoc"]}))
          desc)))


(comment
  (println "foo")



  (sentry/frame->sentry {:class-path-url "cljdoc/util/sentry/sample_source.clj"
                         :file-name "filename.clj"
                         :line-number 20
                         :package "cljdoc.util.sentry"
                         :method-name "baz"}
                        {:app-namespaces ["cljdoc"]})


  (sentry/frame->sentry {:class-path-url "cljdoc/util/sentry/sample_source.clj"
                         :file-name "filename.clj"
                         :line-number 40
                         :package "cljdoc.util.sentry"
                         :method-name "baz"}
                        {:app-namespaces ["cljdoc"]})
  ;; => {:filename "filename.clj",
  ;;     :lineno 40,
  ;;     :function "cljdoc.util.sentry/baz",
  ;;     :in_app true}

  :eoc)


(t/deftest build-envelope-test
  (t/is (match? (m/nested-equals [expected-header
                                  expected-item
                                  expected-payload])
           (sentry/build-envelope {:config test-config
                                   :log-event test-event})))
  )

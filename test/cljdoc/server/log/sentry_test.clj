(ns cljdoc.server.log.sentry-test
  "Inspired by, and lifted from, raven-clj tests"
  (:require [cljdoc.server.log.sentry :as sentry]
            [clojure.test :as t]
            [lambdaisland.uri :as uri]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

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

(def sample-source "cljdoc/server/log/sentry/sample_source.clj")
(def sample-source-small "cljdoc/util/sentry/sample_source_small.clj")

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
      [ ";; line 3" ";; line 4"]]
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
                                          :function "cljdoc.util.sentry/baz"
                                          :in_app true}
                                   expected-pre-context (assoc :pre_context expected-pre-context)
                                   expected-context-line (assoc :context_line expected-context-line)
                                   expected-post-context (assoc :post_context expected-post-context)))
                (sentry/frame->sentry {:class-path-url file-path
                                       :file-name "filename.clj"
                                       :line-number line-number
                                       :package "cljdoc.util.sentry"
                                       :method-name "baz"}
                                      {:app-namespaces ["cljdoc"]}))
        desc)))


(t/deftest frame->sentry-not-in-app
  (t/is (match? (cond-> {:filename "core.clj"
                         :lineno 44
                         :function "clojure/baz"
                         :in_app false})
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
                                                                           :data {:some :data}}}]})]
                (sentry/build-envelope {:config test-config
                                        :log-event (assoc test-event
                                                          :log-exception (ex-info "something bad happened" {:some :data}) )}))))

(t/deftest build-envelope-with-chained-exception-test
  (t/is (match? [expected-header
                 expected-item
                 (assoc expected-payload :exception {:values [{:type "clojure.lang.ExceptionInfo"
                                                               :value "bad1"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "cljdoc-sentry-appender"
                                                                           :exception_id 3
                                                                           :data {:bad1 :data1}}}
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
                                                                           :data {:bad3 :data3}}}
                                                             {:type "clojure.lang.ExceptionInfo"
                                                               :value "cause"
                                                               :thread_id "some-thread"
                                                               :mechanism {:type "chained"
                                                                           :exception_id 0
                                                                           :data {:cause :cause-data}}}]})]
                (sentry/build-envelope {:config test-config
                                        :log-event (assoc test-event
                                                          :log-exception (ex-info "bad1" {:bad1 :data1}
                                                                                  (ex-info "bad2" {}
                                                                                           (ex-info "bad3" {:bad3 :data3}
                                                                                                    (ex-info "cause" {:cause :cause-data})))))}))))

(comment

  (make-dsn {:path "/1"})
  ;; => "https://b70a31b3510c4cf793964a185cfe1fd0:b7d80b520139450f903720eb7991bf3d@example.com/1"

  (make-dsn {:path "/sentry/1" :password nil :query "environment=test&servername=example"})
  ;; => "https://b70a31b3510c4cf793964a185cfe1fd0@example.com/sentry/1?environment=test&servername=example"

  (sentry/build-envelope {:config test-config
                                        :log-event (assoc test-event
                                                          :log-exception (ex-info "something bad happened" {:some :data}) )})
  ;; => [{:dsn "https://some-dsn-url",
  ;;      :sdk {:name "cljdoc.clojure", :version "0.0.1"},
  ;;      :sent_at :tbd}
  ;;     {:type "event", :content_type "application/json", :length :tbd}
  ;;     {:sdk {:name "cljdoc.clojure", :version "0.0.1"},
  ;;      :release "some-guid",
  ;;      :event_id "22e296c9-6028-45ed-a57e-867a2a07b7db",
  ;;      :level "error",
  ;;      :logger "some.logger",
  ;;      :environment "some-env",
  ;;      :server_name "some-server",
  ;;      :exception
  ;;      {:values
  ;;       [{:type "clojure.lang.ExceptionInfo",
  ;;         :value "something bad happened",
  ;;         :thread-id "some-thread",
  ;;         :mechanism
  ;;         {:type "cljdoc-sentry-appender", :exception_id 0, :data {:some :data}},
  ;;         :stacktrace
  ;;         {:frames
  ;;          [{:filename "NO_SOURCE_FILE",
  ;;            :lineno 201,
  ;;            :function "cljdoc.util/invokeStatic",
  ;;            :in_app true}
  ;;           {:filename "NO_SOURCE_FILE",
  ;;            :lineno 199,
  ;;            :function "cljdoc.util/invoke",
  ;;            :in_app true}
  ;;           {:filename "Compiler.java",
  ;;            :lineno 7739,
  ;;            :function "clojure.lang/eval",
  ;;            :in_app false}
  ;;           {:filename "interruptible_eval.clj",
  ;;            :lineno 106,
  ;;            :function "nrepl.middleware.interruptible-eval/[fn]",
  ;;            :in_app false,
  ;;            :context_line
  ;;            "                                  (Compiler/eval input true))]",
  ;;            :pre_context
  ;;            ["                  (try"
  ;;             "                    (let [value (if eval-fn"
  ;;             "                                  (eval-fn input)"
  ;;             "                                  ;; If eval-fn is not provided, call"
  ;;             "                                  ;; Compiler/eval directly for slimmer stack."],
  ;;            :post_context
  ;;            ["                      (set! *3 *2)"
  ;;             "                      (set! *2 *1)"
  ;;             "                      (set! *1 value)"
  ;;             "                      (try"
  ;;             "                        ;; *out* has :tag metadata; *err* does not"]}
  ;;           {:filename "interruptible_eval.clj",
  ;;            :lineno 101,
  ;;            :function "nrepl.middleware.interruptible-eval/run--1435",
  ;;            :in_app false,
  ;;            :context_line "                  (try",
  ;;            :pre_context
  ;;            ["                                ;; If error happens during read phase, call"
  ;;             "                                ;; caught-hook but don't continue executing."
  ;;             "                                (caught e)"
  ;;             "                                eof)))]"
  ;;             "                (when-not (identical? input eof)"],
  ;;            :post_context
  ;;            ["                    (let [value (if eval-fn"
  ;;             "                                  (eval-fn input)"
  ;;             "                                  ;; If eval-fn is not provided, call"
  ;;             "                                  ;; Compiler/eval directly for slimmer stack."
  ;;             "                                  (Compiler/eval input true))]"]}
  ;;           {:filename "session.clj",
  ;;            :lineno 230,
  ;;            :function "nrepl.middleware.session/session-loop--1519",
  ;;            :in_app false,
  ;;            :context_line "                 (r) ;; -1 stack frame this way.",
  ;;            :pre_context
  ;;            ["                   [exec-id ^Runnable r ^Runnable ack msg] (.take queue)"
  ;;             "                   bindings (add-per-message-bindings msg @session)]"
  ;;             "               (swap! state assoc :running exec-id)"
  ;;             "               (push-thread-bindings bindings)"
  ;;             "               (if (fn? r)"],
  ;;            :post_context
  ;;            ["                 (.run r))"
  ;;             "               (swap! session (fn [current]"
  ;;             "                                (-> (merge current (get-thread-bindings))"
  ;;             "                                    ;; Remove vars that we don't want to save"
  ;;             "                                    ;; into the session."]}
  ;;           {:filename "SessionThread.java",
  ;;            :lineno 21,
  ;;            :function "nrepl/run",
  ;;            :in_app false,
  ;;            :context_line "        runFn.invoke();",
  ;;            :pre_context
  ;;            ["        setDaemon(true);"
  ;;             "    }"
  ;;             ""
  ;;             "    @Override"
  ;;             "    public void run() {"],
  ;;            :post_context ["    }" "}"]}]}}]},
  ;;      :timestamp "2025-07-01T11:12:13.123Z",
  ;;      :logentry {:formatted "Something bad happened"},
  ;;      :platform "clojure"}]

  :eoc)

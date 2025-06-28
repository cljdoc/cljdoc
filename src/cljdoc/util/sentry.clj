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


  (def ex (ex-info "Uh oh" {} (ex-info "The cause" {:some :data :is :here})))

  (interfaces/stacktrace {:release (cfg/version)} ex app-namespaces)
  ;; => {:release "dev",
  ;;     :exception
  ;;     [{:value "The cause",
  ;;       :type "clojure.lang.ExceptionInfo",
  ;;       :stacktrace
  ;;       {:frames
  ;;        ({:filename "SessionThread.java",
  ;;          :lineno 21,
  ;;          :function "nrepl/run",
  ;;          :in_app false,
  ;;          :context_line "        runFn.invoke();",
  ;;          :pre_context
  ;;          ("        setDaemon(true);"
  ;;           "    }"
  ;;           ""
  ;;           "    @Override"
  ;;           "    public void run() {"),
  ;;          :post_context ("    }" "}")}
  ;;         {:filename "session.clj",
  ;;          :lineno 230,
  ;;          :function "nrepl.middleware.session/session-loop--1519",
  ;;          :in_app false,
  ;;          :context_line "                 (r) ;; -1 stack frame this way.",
  ;;          :pre_context
  ;;          ("                   [exec-id ^Runnable r ^Runnable ack msg] (.take queue)"
  ;;           "                   bindings (add-per-message-bindings msg @session)]"
  ;;           "               (swap! state assoc :running exec-id)"
  ;;           "               (push-thread-bindings bindings)"
  ;;           "               (if (fn? r)"),
  ;;          :post_context
  ;;          ("                 (.run r))"
  ;;           "               (swap! session (fn [current]"
  ;;           "                                (-> (merge current (get-thread-bindings))"
  ;;           "                                    ;; Remove vars that we don't want to save"
  ;;           "                                    ;; into the session.")}
  ;;         {:filename "interruptible_eval.clj",
  ;;          :lineno 101,
  ;;          :function "nrepl.middleware.interruptible-eval/run--1435",
  ;;          :in_app false,
  ;;          :context_line "                  (try",
  ;;          :pre_context
  ;;          ("                                ;; If error happens during read phase, call"
  ;;           "                                ;; caught-hook but don't continue executing."
  ;;           "                                (caught e)"
  ;;           "                                eof)))]"
  ;;           "                (when-not (identical? input eof)"),
  ;;          :post_context
  ;;          ("                    (let [value (if eval-fn"
  ;;           "                                  (eval-fn input)"
  ;;           "                                  ;; If eval-fn is not provided, call"
  ;;           "                                  ;; Compiler/eval directly for slimmer stack."
  ;;           "                                  (Compiler/eval input true))]")}
  ;;         {:filename "interruptible_eval.clj",
  ;;          :lineno 106,
  ;;          :function "nrepl.middleware.interruptible-eval/[fn]",
  ;;          :in_app false,
  ;;          :context_line
  ;;          "                                  (Compiler/eval input true))]",
  ;;          :pre_context
  ;;          ("                  (try"
  ;;           "                    (let [value (if eval-fn"
  ;;           "                                  (eval-fn input)"
  ;;           "                                  ;; If eval-fn is not provided, call"
  ;;           "                                  ;; Compiler/eval directly for slimmer stack."),
  ;;          :post_context
  ;;          ("                      (set! *3 *2)"
  ;;           "                      (set! *2 *1)"
  ;;           "                      (set! *1 value)"
  ;;           "                      (try"
  ;;           "                        ;; *out* has :tag metadata; *err* does not")}
  ;;         {:filename "Compiler.java",
  ;;          :lineno 7744,
  ;;          :function "clojure.lang/eval",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()}
  ;;         {:filename "Compiler.java",
  ;;          :lineno 464,
  ;;          :function "clojure.lang/eval",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()}
  ;;         {:filename "Compiler.java",
  ;;          :lineno 4208,
  ;;          :function "clojure.lang/eval",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()}
  ;;         {:filename "Compiler.java",
  ;;          :lineno 4209,
  ;;          :function "clojure.lang/eval",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()}
  ;;         {:filename "AFn.java",
  ;;          :lineno 144,
  ;;          :function "clojure.lang/applyTo",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()}
  ;;         {:filename "AFn.java",
  ;;          :lineno 156,
  ;;          :function "clojure.lang/applyToHelper",
  ;;          :in_app false,
  ;;          :context_line nil,
  ;;          :pre_context (),
  ;;          :post_context ()})}}]}




  :eoc)

(ns cljdoc.util.sentry
  "Support for sending log events to sentry.
  Some code lifted from https://github.com/sethtrain/raven-clj, mods:
  - moved to current, non-deprecated, sentry.io evenlope REST API
  - log full exception chain
  - send events indirectly via logging appender."
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [cljdoc.config :as cfg]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [lambdaisland.uri :as uri]
            [prone.stacks :as prone-stack])
  (:import [java.net InetAddress]
           [java.time Instant]))

(set! *warn-on-reflection* true)

(def ^:dynamic *http-request*
  "When bound, we'll send the http request to sentry"
  nil)

(defn extract-project-id [sentry-dsn]
  (when sentry-dsn
    (-> sentry-dsn
        uri/parse
        :path
        (string/split #"/")
        last
        parse-long)))

(def config
  "We initalize at load time rather than via integrant because we need logging to be up as early as possible"
  {:sentry-dsn (cfg/sentry-dsn)
   :sentry-project-id (extract-project-id (cfg/sentry-dsn))
   :environment (if (= :prod (cfg/profile))
                  "production"
                  "dev")
   :release (cfg/version)
   :sentry-client {:name "cljdoc.clojure"
                   :version "0.0.1"}
   :server-name (.getHostName (InetAddress/getLocalHost)) ;; TODO: apparently this isn't terribly reliable?
   :app-namespaces ["cljdoc"]})

(defn- file->source-context [file-path target-line-num {:keys [pre-context-lines post-context-lines]}]
  (when (>= target-line-num 1)
    (let [pre-lines (if (< target-line-num pre-context-lines)
                      (dec target-line-num)
                      pre-context-lines)
          begin-line-num (max 1, (- target-line-num pre-lines))
          end-line-num (+ begin-line-num pre-lines post-context-lines)]
      (when-let [file-path (io/resource file-path)]
        (with-open [rdr (io/reader file-path)]
          (let [{:keys [line-num] :as result}
                (loop [acc {:pre [] :target nil :post []}
                       lines (->> (map (fn [line ndx]
                                         [(inc ndx) line])
                                       (line-seq rdr) (range)))]
                  (let [[line-num line] (first lines)]
                    (cond
                      (not line)
                      acc

                      (< line-num begin-line-num)
                      (recur (assoc acc :line-num line-num)
                             (rest lines))

                      (= line-num end-line-num)
                      (update acc :post conj line)

                      (= line-num target-line-num)
                      (recur (assoc acc :line-num line-num :target line)
                             (rest lines))

                      (< line-num target-line-num)
                      (recur (-> acc
                                 (assoc :line-num line-num)
                                 (update :pre conj line))
                             (rest lines))

                      :else
                      (recur (-> acc
                                 (assoc :line-num line-num)
                                 (update  :post conj line))
                             (rest lines)))))]
            (when (<= target-line-num line-num)
              result)))))))

(defn- in-app [package app-namespaces]
  (boolean (some #(string/starts-with? package %) app-namespaces)))

(defn frame->sentry [frame {:keys [app-namespaces]}]
  (let [{:keys [pre target post]} (file->source-context (:class-path-url frame) (:line-number frame)
                                                        {:pre-context-lines 5
                                                         :post-context-lines 5})]
    (cond-> {:filename     (:file-name frame)
             :lineno       (:line-number frame)
             :function     (str (:package frame) "/" (:method-name frame))
             :in_app       (in-app (:package frame) app-namespaces)}
      target (assoc :context_line target)
      pre    (assoc :pre_context pre)
      post   (assoc :post_context post))))

(defn exceptions->sentry [exceptions config {:keys [log-thread-name]}]
  (let [cnt-exes (count exceptions)]
    (mapv (fn [ex ndx]
            {:type (:type ex)
             :value (:message ex)
             :thread_id log-thread-name
             :mechanism {:type (if (= (dec cnt-exes) ndx)
                                 "cljdoc-sentry-appender"
                                 "chained")
                         ;; https://develop.sentry.dev/sdk/data-model/event-payloads/exception/ under exception_id
                         ;; The SDK should assign simple incrementing integers
                         ;; to each exception in the tree, starting with 0 for the root of the tree.
                         ;; In other words, when flattened into the list provided in the exception
                         ;; values on the event, the last exception in the list should have ID 0,
                         ;; the previous one should have ID 1, the next previous should have ID 2, etc.
                         ;;
                         ;; Lee interpretation: "root of the tree" might be misleading. I think this means
                         ;; root cause? The logback sentry appender lists cause last and assigns it id of 0.
                         :exception_id ndx
                         ;; this will get translated to json so we str to preserve colon in :keywords
                         ;; sentry.io doesn't display when map is nested, so adapt accordingly
                         :data (reduce-kv (fn [m k v]
                                            (assoc m (str k) (pr-str v)))
                                          {}
                                          (:data ex)) }
             :stacktrace {:frames (mapv (fn [frame] (frame->sentry frame config))
                                        (:frames ex))}})
          exceptions (reverse (range 0 cnt-exes)))))

(defn- http-request->sentry [req]
  {:url (str (name (:scheme req))
             "://"
             (:server-name req)
             (when (not= 80 (:server-port req))
               (str ":" (:server-port req)))
             (:uri req))
   :method (:request-method req)
   :headers (get req :headers {})
   :query_string (get req :query-string "")
   :data (get req :path-params {})
   :env {:session (get req :session {})}})

(defn make-sentry-url [project-id]
  (format "https://sentry.io/api/%s/envelope/" project-id))

(defn build-event-envelope-header [{:keys [sentry-dsn sentry-client]}]
  {:dsn sentry-dsn
   :sdk sentry-client
   :sent_at :tbd})

(defn build-event-envelope-item []
  {:type "event"
   :content_type "application/json"
   :length :tbd})

(defn flatten-to-exceptions [ex]
  (when ex
    (let [exceptions (prone-stack/normalize-exception ex)]
      (loop [ex exceptions
             acc [ex]]
        (if-let [caused-by (:caused-by ex)]
          (recur caused-by (conj acc caused-by))
          (mapv #(dissoc % :caused-by) acc))))))

(defn build-event-payload
  [{:keys [sentry-client server-name release environment] :as config}
   {:keys [log-message log-timestamp log-exception logger] :as log-event}
   http-request]
  (let [exceptions (flatten-to-exceptions log-exception)]
    (cond-> {:event_id (str (random-uuid))
             :timestamp log-timestamp
             :level "error" ;; we'll consider all events errors
             :platform "clojure"
             :logger logger
             :server_name server-name
             :release release
             :sdk sentry-client
             :environment environment}
      log-message (assoc :logentry {:formatted log-message})
      exceptions (assoc :exception {:values (exceptions->sentry exceptions config log-event)})
      http-request (assoc :request (http-request->sentry http-request)))))

(defn build-envelope [{:keys [config log-event http-request]}]
  [(build-event-envelope-header config)
   (build-event-envelope-item)
   (build-event-payload config log-event http-request)])

(defn capture-log-event [{:keys [sentry-project-id] :as config} log-event]
  (let [url (make-sentry-url sentry-project-id)
        [header item payload] (build-envelope
                                {:config config
                                 :log-event log-event
                                 :http-request *http-request*})
        payload-json (json/generate-string payload)
        payload-json-length (alength (.getBytes payload-json "UTF-8"))
        item (assoc item :length payload-json-length)
        header (assoc header :sent_at (str (Instant/now)))
        body (str (json/generate-string header) "\n"
                  (json/generate-string item) "\n"
                  payload-json)]
    ;; TODO: sentry will return 429 with a Retry-After header on rate-limiting
    ;; https://develop.sentry.dev/sdk/expected-features/rate-limiting/
    (http/post url {:body body})))

(comment
  (str (Instant/now))
  ;; => "2025-07-01T17:22:50.697092017Z"

  (require '[clojure.tools.logging :as log])

  (log/error (ex-info "ex msg" {:ex :data}) "log msg")

  (log/error "just a message")

  (log/error (ex-info "ex msg2" {:ex {:nested :data}}) "nested ex data")

  :eoc)

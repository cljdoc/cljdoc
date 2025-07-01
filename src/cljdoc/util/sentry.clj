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
            [prone.stacks :as prone-stack])
  (:import [java.net InetAddress]
           [java.time Instant]))

(set! *warn-on-reflection* true)

(def ^:dynamic *http-request*
  "When bound, we'll send the http request to sentry"
  nil)

(defn extract-sentry-project-id [sentry-dsn]
  (when sentry-dsn
    (let [[_ url] (string/split sentry-dsn #"@")]
      (-> (string/split url #"/")
          (last)
          (string/split #"\?")
          (first)
          (parse-long)))))

(def config
  "We initalize at load time rather than via integrant because we need logging to be up as early as possible"
  {:sentry-dsn cfg/sentry-dsn
   :sentry-project-id (extract-sentry-project-id cfg/sentry-dsn)
   :environment "production"
   :release cfg/version
   :sentry-client {:name "cljdoc.clojure"
                   :version "0.0.1"}
   :server-name (.getHostName (InetAddress/getLocalHost)) ;; TODO: apparently this isn't terribly reliable?
   :timeout-ms 3000
   :app-namespaces ["cljdoc"]})

(defn file->source [file-path line-number]
  (some-> file-path
          (io/resource)
          slurp
          (string/split #"\n")
          (#(drop (- line-number 6) %))
          (#(take 11 %))))

(defn in-app [package app-namespaces]
  (boolean (some #(string/starts-with? package %) app-namespaces)))

(defn frame->sentry [app-namespaces frame]
  (let [source (file->source (:class-path-url frame) (:line-number frame))]
    {:filename     (:file-name frame)
     :lineno       (:line-number frame)
     :function     (str (:package frame) "/" (:method-name frame))
     :in_app       (in-app (:package frame) app-namespaces)
     :context_line (nth source 5 nil)
     :pre_context  (take 5 source)
     :post_context (drop 6 source)}))

(defn exceptions->sentry [exceptions {:keys [app-namespaces]} {:keys [log-thread-name]}]
  (let [cnt-exes (count exceptions)]
    (mapv (fn [ex ndx]
            {:type (:type ex)
             :value (:message ex)
             :thread-id log-thread-name
             :mechanism {:type (if (= (dec cnt-exes) ndx)
                                 "cljdoc-sentry-appender"
                                 "chained")
                         :exception_id ndx
                         :data (:data ex)} ;; this will get translated to json so won't look like :keywords
             :stacktrace {:frames (mapv (fn [frame] (frame->sentry app-namespaces frame))
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
   :data (get req :params {})
   :env {:session (get req :session {})}})

(defn make-sentry-url [project-id]
  (format "https://sentry.io/api/%s/envelope/" project-id))

(defn build-event-envelope-header [{:keys [sentry-dsn sentry-client]}]
  {:dsn sentry-dsn
   :sdk sentry-client
   :sent_at (str (Instant/now))})

(defn build-event-envelope-item [{:keys [payload-length]}]
  {:type "event"
   :content_type "application/json"
   :length payload-length})

(defn flatten-exceptions [exceptions]
  (when exceptions
    (let [exceptions prone-stack/normalize-exception]
      (loop [ex exceptions
             acc [ex]]
        (if-let [caused-by (:caused-by ex)]
          (recur caused-by (conj acc caused-by))
          (->> acc
               reverse
               (mapv #(dissoc % :caused-by))))))))

(defn build-event-payload
  [{:keys [sentry-client server-name release environment] :as config}
   {:keys [log-timestamp log-exceptions logger] :as log-event}
   http-request]
  (let [log-exceptions (flatten-exceptions log-exceptions)]
    (cond-> {:event_id (str (random-uuid))
             :timestamp log-timestamp
             :level "error" ;; we'll consider all events errors
             :platform "clojure"
             :logger logger
             :server_name server-name
             :release release
             :sdk sentry-client
             :environment environment}
      log-exceptions (assoc :exception {:values (exceptions->sentry log-exceptions config log-event)})
      http-request (assoc :request (http-request->sentry http-request)))))

(defn build-envelope [config log-event http-request]
  (let [event-payload (json/generate-string (build-event-payload config log-event http-request))
        event-payload-length (alength (.getBytes event-payload "UTF-8"))]
    (str
     (json/generate-string (build-event-envelope-header config)) "\n"
     (json/generate-string (build-event-envelope-item {:payload-length event-payload-length})) "\n"
     event-payload)))

(defn sentry-capture-log-event [{:keys [sentry-project-id] :as config} log-event]
  (let [url (make-sentry-url sentry-project-id)
        body (build-envelope config log-event *http-request*)]
    ;; TODO: sentry will return 429 with a Retry-After header on rate-limiting
    ;; https://develop.sentry.dev/sdk/expected-features/rate-limiting/
    (http/post url {:body body})))

(comment


  :eoc)

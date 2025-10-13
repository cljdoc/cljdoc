(ns cljdoc.http-client
  "A wrapper for babashka.http-client with generous but reasonable default timeout values,
  add more convenience fns as needed."
  (:refer-clojure :exclude [get])
  (:require [babashka.http-client :as http]))

(def default-request-opts {:timeout (* 5 60 1000)
                           :client (http/client
                                    (merge http/default-client-opts
                                           {:connect-timeout (* 15 1000)}))})

(defn request [opts]
  (http/request (merge default-request-opts opts)))

(defn get
  "Convenience wrapper for `request` with method `:get`"
  ([uri] (get uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :get)]
     (request opts))))

(defn head
  "Convenience wrapper for `request` with method `:head`"
  ([uri] (head uri nil))
  ([uri opts]
   (let [opts (assoc opts :uri uri :method :head)]
     (request opts))))

(defn post
  "Convenience wrapper for `request` with method `:post`"
  [uri opts]
  (let [opts (assoc opts :uri uri :method :post)]
    (request opts)))

(comment
  (get "https://mock.httpstatus.io/200")
  ;; => {:status 200,
  ;;     :body "200 OK",
  ;;     :version :http2,
  ;;     :headers
  ;;     {":status" "200",
  ;;      "access-control-allow-origin" "*",
  ;;      "content-encoding" "gzip",
  ;;      "content-type" "text/plain",
  ;;      "date" "Mon, 13 Oct 2025 01:50:42 GMT",
  ;;      "x-response-time" "2 ms"},
  ;;     :uri #object[java.net.URI 0x223c351f "https://mock.httpstatus.io/200"],
  ;;     :request
  ;;     {:headers
  ;;      {:accept "*/*",
  ;;       :accept-encoding ["gzip" "deflate"],
  ;;       :user-agent "babashka.http-client/0.4.23"},
  ;;      :timeout 300000,
  ;;      :client
  ;;      {:client
  ;;       #object[jdk.internal.net.http.HttpClientFacade 0x4ed63601 "jdk.internal.net.http.HttpClientImpl@261f7d7d(2)"],
  ;;       :request
  ;;       {:headers
  ;;        {:accept "*/*",
  ;;         :accept-encoding ["gzip" "deflate"],
  ;;         :user-agent "babashka.http-client/0.4.23"}},
  ;;       :type :babashka.http-client/client},
  ;;      :uri #object[java.net.URI 0x223c351f "https://mock.httpstatus.io/200"],
  ;;      :method :get}}

  (get "https://mock.httpstatus.io/200?delay=2000" {:timeout 1000})
  ;; => Execution error (HttpTimeoutException) at jdk.internal.net.http.HttpClientImpl/send (HttpClientImpl.java:921).
  ;;    request timed out

  (get "http://10.255.255.1" {:timeout 1000})
  ;; => Execution error (ConnectException) at jdk.internal.net.http.ResponseTimerEvent/handle (ResponseTimerEvent.java:69).
  ;;    HTTP connect timed out

  (get "http://10.255.255.1" {:client (http/client (merge http/default-client-opts {:connect-timeout 100}))
                              :timeout 2000})
  ;; => Execution error (ConnectException) at jdk.internal.net.http.MultiExchange/toTimeoutException (MultiExchange.java:619).
  ;;    HTTP connect timed out

  (get "http://10.255.255.1" {:client (http/client (merge http/default-client-opts {:connect-timeout 100}))})
  ;; => Execution error (ConnectException) at jdk.internal.net.http.MultiExchange/toTimeoutException (MultiExchange.java:619).
  ;;    HTTP connect timed out

  (get "http://10.255.255.1" {:client (http/client (merge http/default-client-opts {:connect-timeout 2000}))
                              :timeout 100})
  ;; => Execution error (ConnectException) at jdk.internal.net.http.ResponseTimerEvent/handle (ResponseTimerEvent.java:69).
  ;;    HTTP connect timed out

  :eoc)

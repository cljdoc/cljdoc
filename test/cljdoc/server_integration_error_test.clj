(ns ^:integration ^:server-integration cljdoc.server-integration-error-test
  "The current pedestal.connector.test does not cover stylobate errors, hence this test namespace
  where we start a real pedestal server to verify."
  (:require [cljdoc.http-client :as http]
            [cljdoc.server.built-assets :as built-assets]
            [cljdoc.server.pedestal :as pedestal]
            [clojure.string :as str]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [io.pedestal.connector :as conn]
            [io.pedestal.http.impl.servlet-interceptor :as psi]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test])
  (:import [java.io IOException OutputStreamWriter BufferedReader InputStreamReader]
           [java.net ServerSocket Socket]))

(set! *warn-on-reflection* true)

(defn- start-server! [opts]
  (loop [attempt-countdown 10]
    ;; jetty can pick a port when started with port 0, but pedestal does not expose the picked port
    (let [port (with-open [socket (ServerSocket. 0)]
                 (.getLocalPort socket))
          {:keys [connector ex]} (try
                                   (let [connector (pedestal/create-connector (assoc opts :port port))]
                                     {:connector (conn/start! connector)})
                                   (catch IOException ex
                                     {:ex ex}))]
      (if ex
        (if (= attempt-countdown 1)
          (throw ex)
          (do
            (println ">>> WARN: failed to get free port, retrying")
            (Thread/sleep 10)
            (recur (dec attempt-countdown))))
        {:connector connector :port port}))))

(defn- stop-server! [{:keys [connector]}]
  (conn/stop! connector))

(defn- create-socket ^Socket [^String host ^Integer port]
  (Socket. host port))

(def ^:dynamic *host* nil)
(def ^:dynamic *port* nil)
(def ^:dynamic *url* nil)
(def ^:dynamic *stylobate-stats* nil)

(defn run-server [tests]
  (let [stats (atom [])
        server (start-server! {:host "localhost"
                               :stylobate-stats-fn (fn [m] (swap! stats conj m))})]
    (binding [*port* (:port server)
              *host* "localhost"
              *url* (str "http://localhost:" (:port server))
              *stylobate-stats* stats]
      (tests))
    (stop-server! server)))

(t/use-fixtures :each run-server)

(t/deftest sanity-no-error-test
  (let [logged (atom [])]
    (with-redefs [log/log* (fn [_logger level _throwable message]
                             (swap! logged conj [level message]))]
      (t/is (match? {:status 200
                     :body #"is a website building &amp; hosting documentation for Clojure/Script libraries"}
                    (http/get *url*)))
      (t/is (match? [] @logged))
      (t/is (match? [] @*stylobate-stats*)))))

(t/deftest simulated-broken-pipe-not-logged-test
  (doseq [ex [(IOException. "BrOkEn PiPe")
              (ex-info "foo" {} (IOException. "BROKEN PIPE"))
              (ex-info "Badness" {}
                       (IOException. "cripes"
                                     (IOException. "broken pipe"
                                                   (IOException. "blap"))))]]
    (reset! *stylobate-stats* [])
    (let [logged (atom [])]
      (with-redefs [log/log* (fn [_logger level _throwable message]
                               (swap! logged conj [level message]))
                    psi/write-body (fn [_servlet-resp _body]
                                     (throw ex))]
        (t/is (match?
               {:status 500
                :body "An exception occurred, sorry about that!"}
               (http/get *url* {:throw false})))
        (t/is (match? [] @logged))
        (t/is (match? [[:request-method :get
                        :request-path "/"
                        :route-path "/"
                        :broken-connection-msg "broken pipe"]]
                      @*stylobate-stats*))))))

(t/deftest simulated-connection-reset-not-logged-test
  (let [logged (atom [])]
    (with-redefs [log/log* (fn [_logger level _throwable message]
                             (swap! logged conj [level message]))
                  psi/write-body (fn [_servlet-resp _body]
                                   (throw (IOException. "CoNNectiON RESET by PeEr")))]
      (t/is (match?
             {:status 500
              :body "An exception occurred, sorry about that!"}
             (http/get *url* {:throw false})))
      (t/is (match? [] @logged))
      (t/is (match? [[:request-method :get
                      :request-path "/"
                      :route-path "/"
                      :broken-connection-msg "connection reset by peer"]]
                    @*stylobate-stats*)))))

(t/deftest simulated-other-error-logged-test
  (doseq [ex [(ex-info "some other error" {})
              (IOException. "some other io error")]]
    (reset! *stylobate-stats* [])
    (let [logged (atom [])
          req-info [:request-method :get
                    :request-path "/"
                    :route-path "/"
                    :broken-connection-msg nil]]
      (with-redefs [log/log* (fn [_logger level throwable message]
                               (swap! logged conj [level message (ex-message (ex-cause throwable))]))
                    psi/write-body (fn [_servlet-resp _body]
                                     (throw ex))]
        (t/is (match?
               {:status 500
                :body "An exception occurred, sorry about that!"}
               (http/get *url* {:throw false})))
        (t/is (match? [[:error (str "unhandled pedestal exception for: " req-info)
                        (ex-message ex)]]
                      @logged))
        (t/is (match? [req-info]
                      @*stylobate-stats*))))))

(t/deftest socket-sanity-test
  (let [logged (atom [])]
    (with-redefs [log/log* (fn [_logger level _throwable message]
                             (swap! logged conj [level message]))]
      (let [response-lines (with-open [socket (create-socket *host* *port*)
                                       writer (OutputStreamWriter. (.getOutputStream socket))
                                       reader (BufferedReader. (InputStreamReader. (.getInputStream socket)))]
                             ;; Send HTTP GET request and close without reading response
                             (.write writer (str/join "\r\n" ["GET /cljdoc.2ZZPOFCD.js.map HTTP/1.1"
                                                              (str "Host: " *host*)
                                                              "Connection: close"
                                                              ""
                                                              ""]))
                             (.flush writer)
                             (loop [lines []]
                               (if-let [line (.readLine reader)]
                                 (recur (conj lines line))
                                 lines)))]
        (t/is (match? (m/embeds [#"200 OK"])
                      response-lines))
        (t/is (match? [] @logged))
        (t/is (match? [] @*stylobate-stats*))))))

(t/deftest real-broken-pipe-not-logged-test
  (let [logged (atom [])
        ;; choose a bigger file to allow for pipe breakage
        path (get (built-assets/load-map) "/cljdoc.js.map")]
    (with-redefs [log/log* (fn [_logger level _throwable message]
                             (swap! logged conj [level message]))]
      (let [socket (create-socket *host* *port*)
            writer (OutputStreamWriter. (.getOutputStream socket))]
        ;; Send HTTP GET request and close without reading response
        (.write writer (str/join "\r\n" [(str "GET " path " HTTP/1.1")
                                         (str "Host: " *host*)
                                         ""
                                         ""]))
        (.flush writer)
        (.close writer)
        (.close socket))
      ;; give log some time to write
      (Thread/sleep 500)
      (t/is (match? [] @logged))
      (t/is (match? [[:request-method :get
                      :request-path path
                      :route-path "/*path"
                      :broken-connection-msg "broken pipe"]]
                    @*stylobate-stats*)))))

(ns ^:slow ^:integration cljdoc.server-integration-test
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [cljdoc.server.log-init :as log-init]
            [cljdoc.server.search.clojars :as clojars]
            [cljdoc.server.system :as sys]
            [clojure.edn :as edn]
            [clojure.spec.test.alpha :as st]
            [clojure.string :as string]
            [clojure.test :as t]
            [integrant.core :as ig]
            [io.pedestal.connector.test :as pdt]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]
            [ring.util.codec :as codec])
  (:import [java.io InputStream RandomAccessFile]
           [java.util.zip ZipInputStream]
           [org.jsoup Jsoup]))

(def ^:dynamic *connector* nil)

(def test-data-dir "test-data/server")
(def log-size-at-start (atom 0))

(defn- log-line-match
  "Lil helper to check for log line. Local dev logs can get big so we efficiently skip to the interesting bit via random file access."
  [^String f byte-offset re]
  (let [f (RandomAccessFile. f "r")]
    (.seek f byte-offset)
    (loop [line (.readLine f)]
      (when line
        (if (re-find re line)
          line
          (recur (.readLine f)))))))

(defn- zip-entries
  "Helper to list entries in a zip stream"
  [^InputStream in-stream]
  (with-open [zip-stream (ZipInputStream. in-stream)]
    (-> zip-stream .getNextEntry .getName)
    (loop [entries []]
      (if-let [entry (.getNextEntry zip-stream)]
        (recur (conj entries (.getName entry)))
        entries))))

(defn start! []
  (assert (not (fs/exists? test-data-dir))
          (format "test data directory exists, remove before running tests: %s" (fs/absolutize test-data-dir)))
  (reset! log-size-at-start (if (fs/exists? log-init/log-file) (fs/size log-init/log-file) 0))
  (reset! clojars/clojars-last-modified nil) ;; REPL friendly, forces re-download
  (st/instrument)
  (let [port (+ 8000 (rand-int 1000))
        opensearch-base-url (format "http://localhost:%d" port)
        env-cfg {:cljdoc/server {:port port
                                 :opensearch-base-url opensearch-base-url
                                 :analysis-service :local
                                 :autobuild-clojars-releases? false
                                 :enable-db-backup? false
                                 :enable-db-restore? false
                                 :clojars-stats-retention-days 5
                                 :dir test-data-dir}
                 :cljdoc/version "some-version"}
        sys-cfg (sys/system-config env-cfg)]
    ;; start everything up, including cljdoc/pedestal-connector but don't start pedestal
    (ig/init sys-cfg (->> sys-cfg
                          keys
                          (remove #{:cljdoc/pedestal})))))

(defn halt! [system]
  (ig/halt! system)
  ;; we don't call shutdown-agents, because our test runner still needs them
  ;; (shutdown-agents)
  (fs/delete-tree test-data-dir))

(defn connector-fn [sys]
  (assert (:cljdoc/pedestal-connector sys))
  (:cljdoc/pedestal-connector sys))

(defn run-system [tests]
  (let [system (start!)]
    (binding [*connector* (connector-fn system)]
      (tests))
    (halt! system)))

(t/use-fixtures :once run-system)

(t/deftest home-page-test
  (t/is (match? {:body #"is a website building &amp; hosting documentation for Clojure/Script libraries"}
                (pdt/response-for *connector* :get "/"))))

(t/deftest full-cycle-test
  (let [build-req-resp (pdt/response-for *connector*
                                         :post "/api/request-build2"
                                         :body (codec/form-encode {:project "metosin/muuntaja" :version "0.6.4"})
                                         :headers {"content-type" "application/x-www-form-urlencoded"})]
    (t/testing "build request"
      (t/is (match? {:status 303
                     :headers {:location "/builds/1"}}
                    build-req-resp)
            "Build request returns redirect to build info location"))

    (t/testing "build info"
      (let [build-uri (get-in build-req-resp [:headers :location])
            builds-html-page (pdt/response-for *connector* :get build-uri)]

        (t/testing "during build"
          (t/is (match? {:status 200 :body #"Analysis Requested"}
                        builds-html-page)
                "build info page acknowledges request")
          (t/is (match? {:status 200 :body #"metosin/muuntaja"}
                        builds-html-page)
                "build info page describes project")
          (t/is (match? {:status 200 :body #"v0\.6\.4"}
                        builds-html-page)
                "build info page includes project version")

          (t/is (match? {:status 200
                         :headers {:content-type "application/edn"}
                         :body (m/via edn/read-string {:group_id "metosin"
                                                       :artifact_id "muuntaja"
                                                       :version "0.6.4"
                                                       :analysis_requested_ts #"\d\d\d\d-*"})}
                        (pdt/response-for *connector* :get build-uri :headers {"Accept" "application/edn"}))
                "build info as edn")

          (t/is (match? {:status 200
                         :headers {:content-type "application/json"}
                         :body (m/via json/parse-string {"group_id" "metosin"
                                                         "artifact_id" "muuntaja"
                                                         "version" "0.6.4"
                                                         "analysis_requested_ts" #"\d\d\d\d-*"})}
                        (pdt/response-for *connector* :get build-uri :headers {"Accept" "application/json"}))
                "build info as json"))

        (t/testing "after completion"
          (let [deadline (+ (System/currentTimeMillis) (* 60 2 1000))]
            (println ">> waiting for sample lib build to complete")
            (loop []
              (if (> (System/currentTimeMillis) deadline)
                (throw (Exception. "Sample lib build took too long"))
                (if (string/includes? (:body (pdt/response-for *connector* :get build-uri))
                                      "Successfully imported 10 namespaces")
                  (println "<< sample lib build completed")
                  (do
                    (Thread/sleep 500)
                    (recur))))))

          (t/is (match? {:status 200
                         :headers {:content-type "text/html"}
                         :body #"Successfully imported 10 namespaces"}
                        (pdt/response-for *connector* :get build-uri))
                "build info page describes api analyis")

          (t/is (match? {:status 200
                         :headers {:content-type "text/html"}
                         :body #"Git Import Completed"}
                        (pdt/response-for *connector* :get build-uri))
                "build info page acknowledges git import")

          (doseq [accept ["text/html" "foobar"]]
            (t/is (match? {:status 200
                           :headers {:content-type "text/html"}
                           :body #"Git Import Completed"}
                          (pdt/response-for *connector*
                                            :get build-uri
                                            :headers {"Accept" accept}))
                  (format "build info defaults to html %s" accept)))))))

  (t/testing "Can ask to build a library with a + in its version"
    ;; we had a problem with +s with clojars
    ;; and then after an upgrade to pedestal, hence this explicit test
    (t/is (match? {:status 404
                   :headers {:content-type "text/html"}
                   :body #"Want to build some documentation?"}
                  (pdt/response-for *connector* :get "/d/com.github.strojure/parsesso/1.2.2+295"))))

  (t/testing "sitemap for built libs (used by search engines)"
    (t/is (match? {:status 200
                   :headers {:content-type "text/xml"}
                   :body #"(?s)<\?xml.*metosin/muuntaja/0\.6\.4"}
                  (pdt/response-for *connector* :get "/sitemap.xml"))))

  (t/testing "built lib content"
    (t/is (match? {:status 302
                   :headers {:location "/d/metosin/muuntaja/0.6.4/doc/readme"}}
                  (pdt/response-for *connector* :get "/d/metosin/muuntaja/0.6.4"))
          "lib doc page redirects to default content")

    (t/is (match? {:status 200
                   :body #"decode-response-body"}
                  (pdt/response-for *connector* :get "/d/metosin/muuntaja/0.6.4/api/muuntaja.core"))
          "lib api content seems good")

    (t/is (match? {:status 200
                   :body #"To allow easier customization"}
                  (pdt/response-for *connector* :get "/d/metosin/muuntaja/0.6.4/doc/creating-new-formats"))
          "lib article content seems good")

    (t/is (match? {:status 200
                   :body (m/via #(-> ^String %
                                     Jsoup/parse
                                     (.select "head > meta[content]")
                                     (.eachAttr "content")
                                     (->> (map str)))
                                (m/embeds ["metosin/muuntaja: Clojure library for format encoding, decoding and content-negotiation Documentation for metosin/muuntaja v0.6.4 on cljdoc."]))}
                  (pdt/response-for *connector* :get "/d/metosin/muuntaja/0.6.4/doc/configuration"))
          "at least one html meta tag has the project's description"))

  (t/testing "searchset api (used by cljdoc)"
    (t/is (match? {:status 200
                   :headers {:content-type "application/json"}
                   :body (m/via json/parse-string {"namespaces" (m/via count 10)})}
                  (pdt/response-for *connector* :get "/api/searchset/metosin/muuntaja/0.6.4"))
          "searchset API for built lib")

    (t/is (match? {:status 200
                   :headers {:content-type "application/json"}
                   :body (m/via json/parse-string {"namespaces" (m/via count 10)})}
                  (pdt/response-for *connector*
                                    :get "/api/searchset/metosin/muuntaja/0.6.4"
                                    :headers {"Accept" "text/html"}))
          "searchset API for built lib disregards Accept header and always returns json"))

  (t/testing "versions api for built artifacts"
    (t/is (match? {:status 200
                   :headers {:content-type "application/json"}
                   :body (m/via json/parse-string {"metosin" {"muuntaja" ["0.6.4"]}})}
                  (pdt/response-for *connector*
                                    :get "/versions/metosin/muuntaja"
                                    :headers {"Accept" "application/json"}))
          "version for artifact API as json")

    (t/is (match? {:status 200
                   :headers {:content-type "application/edn"}
                   :body (m/via edn/read-string {"metosin" {"muuntaja" ["0.6.4"]}})}
                  (pdt/response-for *connector*
                                    :get "/versions/metosin/muuntaja"
                                    :headers {"Accept" "application/edn"}))
          "versions for artifact API as edn"))

  (t/testing "offline download (used by Dash)"
    (t/is (match? {:status 200
                   :headers {:content-disposition "attachment; filename=\"muuntaja-0.6.4.zip\""
                             :content-type "application/zip"}
                   :zip-entries (m/embeds ["muuntaja-0.6.4/assets/js/index.js"
                                           "muuntaja-0.6.4/index.html"
                                           "muuntaja-0.6.4/doc/configuration.html"
                                           "muuntaja-0.6.4/api/muuntaja.format.transit.html"])}
                  (let [{:keys [body] :as resp} (pdt/response-for *connector*
                                                                  :get "/download/metosin/muuntaja/0.6.4"
                                                                  :as :stream)]
                    (-> resp
                        (assoc :zip-entries (zip-entries body))
                        ;; don't want to dump a byte stream body in failed test
                        (dissoc :body))))))

  ;;
  ;; Available artifact search related (need to wait for search index to be populated)
  ;;
  (let [deadline (+ (System/currentTimeMillis) (* 60 3 1000))]
    (println ">> waiting for search index job to complete")
    (loop []
      (if (> (System/currentTimeMillis) deadline)
        (throw (Exception. "Waiting for search index to complete took too long"))
        (if-let [done-line (log-line-match log-init/log-file
                                           @log-size-at-start
                                           #"Finished downloading & indexing artifacts for :clojars")]
          (println (format "<< clojars search index job completed: <<%s>>" done-line))
          (do
            (Thread/sleep 500)
            (recur))))))

  (t/testing "search api for available artifacts (used by cljdoc, Dash)"
    (doseq [accept ["application/json" "foobar"]]
      (t/is (match? {:status 200
                     :headers {:content-type "application/json"}
                     :body (m/via json/parse-string {"count" int?
                                                     "results" (m/embeds [{"artifact-id" "rewrite-clj"
                                                                           "group-id" "rewrite-clj"
                                                                           "version" string?
                                                                           "blurb" string?
                                                                           "origin" "clojars"
                                                                           "score" double}])})}
                    (pdt/response-for *connector*
                                      :get "/api/search?q=rewrite-clj"
                                      :headers {"Accept" accept}))
            (format "search for rewrite-clj Accept %s" accept))))

  (t/testing "search suggest api for available artifacts (optionally used by web browsers)"
    (doseq [accept ["application/json" "foobar"]]
      ;; kinda brittle, API only returns the top 5 results so if popularity of a lib changes
      ;; might need to adjust
      (t/is (match? {:status 200
                     :headers {:content-type "application/x-suggestions+json"}
                     :body (m/via json/parse-string ["rewrite-clj" (m/embeds
                                                                    ["rewrite-clj/rewrite-clj "
                                                                     "net.vemv/rewrite-clj "])])}
                    (pdt/response-for *connector*
                                      :get "/api/search-suggest?q=rewrite-clj"
                                      :headers {"Accept" accept}))
            (format "search suggest for rewrite-clj Accept %s" accept))))

  (t/testing "versions api for available artifacts (used by Dash)"
    (t/is (match? {:status 200
                   :headers {:content-type "application/json"}
                   :body (m/via json/parse-string (m/equals {"borkdude" {"babashka" (m/embeds ["0.2.6"])
                                                                         "rewrite-edn" (m/embeds ["0.4.6"])}}))}
                  (pdt/response-for *connector*
                                    :get "/versions/borkdude?all=true"
                                    :headers {"Accept" "application/json"}))
          "buildable versions for artifact group")

    (t/is (match? {:status 200
                   :headers {:content-type "application/json"}
                   :body (m/via json/parse-string (m/equals {"borkdude"
                                                             (m/equals {"rewrite-edn" (m/embeds ["0.4.6"])})}))}
                  (pdt/response-for *connector*
                                    :get "/versions/borkdude/rewrite-edn?all=true"
                                    :headers {"Accept" "application/json"}))
          "buildable versions for artifact"))

  (t/testing "versions api endpoints also serve html"
    (doseq [accept ["text/html" "foobar"]]
      (t/is (t/is (match? {:status 200
                           :headers {:content-type "text/html"}
                           :body #"(?s)^<!DOCTYPE html>.*borkdude*.*babashka"}
                          (pdt/response-for *connector*
                                            :get "/versions/borkdude?all=true"
                                            :headers {"Accept" accept}))
                  (format "buildable versions for artifact group for accept %s" accept))))
    (doseq [accept ["text/html" "foobar"]]
      (t/is (t/is (match? {:status 200
                           :headers {:content-type "text/html"}
                           :body #"(?s)^<!DOCTYPE html>.*borkdude*.*rewrite-edn"}
                          (pdt/response-for *connector*
                                            :get "/versions/borkdude/rewrite-edn?all=true"
                                            :headers {"Accept" accept}))
                  (format "buildable versions for artifact for accept %s" accept))))))

(t/deftest api-ping-test
  (doseq [accept ["text/html" "foobar" "application/json"]]
    (t/is (match? {:status 200
                   :headers {:content-type "text/html"}
                   :body "pong"}
                  (pdt/response-for *connector*
                                    :get "/api/ping"
                                    :headers {"Accept" accept}))
          (format "ping for accept %s" accept))))

(t/deftest bad-build-request-test
  (t/is (match? {:status 400
                 :headers {:content-type "text/html"}
                 :body "ERROR: Must specify project and version params"}
                (pdt/response-for *connector*
                                  :post "/api/request-build2"
                                  :headers {"content-type" "application/x-www-form-urlencoded"}
                                  :body (codec/form-encode {:aversion "1.2.3"})))
        "incorrect parms")
  (t/is (match? {:status 400
                 :headers {:content-type "text/html"}
                 :body "ERROR: Must specify project and version params"}
                (pdt/response-for *connector*
                                  :post "/api/request-build2"
                                  :body (codec/form-encode {:project "rewrite-clj/rewrite-clj" :version "1.1.45"})))
        "params provided, but missing content type")
  (t/is (match? {:status 404
                 :headers {:content-type "text/html"}
                 :body "ERROR: project nevernever/gonnafindme version 1.2.3 not found in maven repositories"}
                (pdt/response-for *connector*
                                  :post "/api/request-build2"
                                  :headers {"content-type" "application/x-www-form-urlencoded"}
                                  :body (codec/form-encode {:project "nevernever/gonnafindme" :version "1.2.3"})))
        "requested lib does not exist html response"))

(t/deftest bad-build-info-request-test
  (doseq [accept ["text/html" "foobar"]]
    (t/is (match? {:status 404
                   :headers {:content-type "text/html"}
                   :body #"(?s)^<!DOCTYPE html>.*Page not found"}
                  (pdt/response-for *connector*
                                    :get "/builds/notgonnafindthisid"
                                    :headers {"Accept" accept}))
          (format "returns html and defaults - accept %s" accept)))
  (t/is (match? {:status 404
                 :headers {:content-type "application/edn"}
                 :body (m/via edn/read-string "Build not found")}
                (pdt/response-for *connector*
                                  :get "/builds/notgonnafindthisid"
                                  :headers {"Accept" "application/edn"}))
        "accept edn")
  (t/is (match? {:status 404
                 :headers {:content-type "application/json"}
                 :body (m/via json/parse-string "Build not found")}
                (pdt/response-for *connector*
                                  :get "/builds/notgonnafindthisid"
                                  :headers {"Accept" "application/json"}))
        "accept json"))

(t/deftest bad-api-search-request-test
  (doseq [accept ["application/json" "foobar" "text/html"]]
    (t/is (match? {:status 400
                   :headers {:content-type "application/json"}
                   :body "ERROR: Missing q query param"}
                  (pdt/response-for *connector*
                                    :get "/api/search"
                                    :headers {"Accept" accept}))
          (format "missing params - always returns json - accept %s" accept))))

(t/deftest bad-api-search-suggest-request-test
  (doseq [accept ["application/x-suggestions+json" "application/json" "foobar" "text/html"]]
    (t/is (match? {:status 400
                   :headers {:content-type "application/x-suggestions+json"}
                   :body "ERROR: Missing q query param"}
                  (pdt/response-for *connector*
                                    :get "/api/search-suggest"
                                    :headers {"Accept" "application/x-suggestions+json"}))
          (format  "missing params - always returns x-suggestions+json - accept %s" accept))))

(t/deftest bad-api-searchset-request-test
  (t/is (match? {:status 404
                 :headers {:content-type "application/json"}
                 :body (m/via json/parse-string {"error" #"Could not find data"})}
                (pdt/response-for *connector*
                                  :get "/api/searchset/nevernever/gonnafindme/1.2.3"))))

(t/deftest bad-download-request-test
  (t/is (match? {:status 404
                 :headers {:content-type "text/html"}
                 :body #"Could not find data"}
                (pdt/response-for *connector* :get "/download/nope/nope/nope"))))

(comment
  ;; start server for integration tests
  (def s (start!))

  ;; set our *connector* dynamic var
  (alter-var-root #'*connector* (constantly (connector-fn s)))

  ;; kick off a build if you need it:
  (pdt/response-for *connector*
                    :post "/api/request-build2"
                    :body (codec/form-encode {:project "metosin/muuntaja" :version "0.6.4"})
                    :headers {"content-type" "application/x-www-form-urlencoded"})

  (pdt/response-for *connector* :get "/sitemap.xml")
  ;; => {:status 200,
  ;;     :headers
  ;;     {:content-type "text/xml",
  ;;      :cache-control "max-age=31536000,immutable,public"},
  ;;     :body
  ;;     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>https://cljdoc.org/d/metosin/muuntaja/0.6.4</loc></url></urlset>"}

  (pdt/response-for *connector*
                    :get "/versions/metosin/muuntaja"
                    :headers {"Accept" "application/json"})
  ;; => {:status 200,
  ;;     :headers {:content-type "application/json", :cache-control "no-cache"},
  ;;     :body "{\"metosin\":{\"muuntaja\":[\"0.6.4\"]}}"}

  (-> (pdt/response-for *connector*
                        :get "/api/search-suggest?q=rewrite-clj"
                        :headers {"Accept" "application/json"})
      :body
      json/parse-string)
  ;; => ("rewrite-clj"
  ;;     ["rewrite-clj/rewrite-clj "
  ;;      "org.clojars.tristefigure/rewrite-clj "
  ;;      "net.vemv/rewrite-clj "
  ;;      "rewrite-cljs/rewrite-cljs "
  ;;      "dev.nubank/umschreiben-clj "])

  (-> (pdt/response-for *connector*
                        :get "/download/metosin/muuntaja/0.6.4"
                        :as :stream)
      :body
      zip-entries)
  ;; => ["muuntaja-0.6.4/assets/cljdoc.css"
  ;;     "muuntaja-0.6.4/assets/js/index.js"
  ;;     "muuntaja-0.6.4/assets/static/codeberg.svg"
  ;;     "muuntaja-0.6.4/assets/static/sourcehut.svg"
  ;;     "muuntaja-0.6.4/assets/js/cljdoc.EIVSC25X.js.map"
  ;;     "muuntaja-0.6.4/assets/highlightjs/highlight.min.js"
  ;;     "muuntaja-0.6.4/assets/highlightjs/languages/clojure.min.js"
  ;;     "muuntaja-0.6.4/assets/highlightjs/languages/clojure-repl.min.js"
  ;;     "muuntaja-0.6.4/assets/highlightjs/languages/asciidoc.min.js"
  ;;     "muuntaja-0.6.4/assets/highlightjs/languages/groovy.min.js"
  ;;     "muuntaja-0.6.4/index.html"
  ;;     "muuntaja-0.6.4/doc/readme.html"
  ;;     "muuntaja-0.6.4/doc/configuration.html"
  ;;     "muuntaja-0.6.4/doc/creating-new-formats.html"
  ;;     "muuntaja-0.6.4/doc/differences-to-existing-formatters.html"
  ;;     "muuntaja-0.6.4/doc/performance.html"
  ;;     "muuntaja-0.6.4/doc/with-ring.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.core.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.format.core.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.format.edn.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.format.json.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.format.transit.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.interceptor.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.middleware.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.parse.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.protocols.html"
  ;;     "muuntaja-0.6.4/api/muuntaja.util.html"]

  (pdt/response-for *connector* :get "/download/nope/nope/nope")
  ;; => {:status 404,
  ;;     :headers {:content-type "text/html", :cache-control "no-cache"},
  ;;     :body "Could not find data, please request a build first"}

  ;; call this when done to shutdown and clean up test dir
  (halt! s)

  :eoc)

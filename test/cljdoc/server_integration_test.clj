(ns ^:slow ^:integration cljdoc.server-integration-test
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [cljdoc.server.log.init :as log-init]
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

(defn- latest-version [clojars-lib]
  (-> (http/get (str "https://clojars.org/api/artifacts/" clojars-lib)
                {:headers {:Accept "application/edn"}})
      :body
      edn/read-string
      :latest_version))

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
  (let [test-group-id "org.cljdoc"
        test-artifact-id "cljdoc-exerciser"
        test-project (str test-group-id "/" test-artifact-id)
        test-project-version (latest-version test-project)
        build-req-resp (pdt/response-for *connector*
                                         :post "/api/request-build2"
                                         :body (codec/form-encode {:project test-project :version test-project-version})
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
          (t/is (match? {:status 200 :body (re-pattern (string/re-quote-replacement test-project))}
                        builds-html-page)
                "build info page describes project")
          (t/is (match? {:status 200 :body (re-pattern (string/re-quote-replacement (str "v" test-project-version)))}
                        builds-html-page)
                "build info page includes project version")

          (t/is (match? {:status 200
                         :headers {:content-type "application/edn"}
                         :body (m/via edn/read-string {:group_id test-group-id
                                                       :artifact_id test-artifact-id
                                                       :version test-project-version
                                                       :analysis_requested_ts #"\d\d\d\d-*"})}
                        (pdt/response-for *connector* :get build-uri :headers {"Accept" "application/edn"}))
                "build info as edn")

          (t/is (match? {:status 200
                         :headers {:content-type "application/json"}
                         :body (m/via json/parse-string {"group_id" test-group-id
                                                         "artifact_id" test-artifact-id
                                                         "version" test-project-version
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
                                      "Successfully imported 4 namespaces")
                  (println "<< sample lib build completed")
                  (do
                    (Thread/sleep 500)
                    (recur))))))

          (t/is (match? {:status 200
                         :headers {:content-type "text/html"}
                         :body #"Successfully imported 4 namespaces"}
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
                  (format "build info defaults to html %s" accept))))))

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
                     :body (re-pattern (str "(?s)<\\?xml.*"
                                            (string/re-quote-replacement test-project)
                                            "/"
                                            (string/re-quote-replacement test-project-version)))}
                    (pdt/response-for *connector* :get "/sitemap.xml"))))

    (t/testing "built lib content"
      (t/is (match? {:status 302
                     :headers {:location (str "/d/" test-project "/" test-project-version "/doc/readme")}}
                    (pdt/response-for *connector* :get (str "/d/" test-project "/" test-project-version)))
            "lib doc page redirects to default content")

      (t/is (match? {:status 200
                     :body #"cljdoc-exerciser\.core"}
                    (pdt/response-for *connector* :get (str "/d/" test-project "/" test-project-version "/api/cljdoc-exerciser.core")))
            "lib api content seems good")

      (t/is (match? {:status 200
                     :body #"a preamble comes before the first section"}
                    (pdt/response-for *connector* :get (str "/d/" test-project "/" test-project-version "/doc/document-tests/asciidoctor-features")))
            "lib article content seems good")

      (t/is (match? {:status 200
                     :body (m/via #(-> ^String %
                                       Jsoup/parse
                                       (.select "head > meta[content]")
                                       (.eachAttr "content")
                                       (->> (map str)))
                                  (m/embeds [(str test-project ":  Documentation for " test-project " v" test-project-version " on cljdoc.")]))}
                    (pdt/response-for *connector* :get (str "/d/" test-project "/" test-project-version "/doc/document-tests")))
            "at least one html meta tag has the project's description"))

    (t/testing "built lib badge"
      (t/is (match? {:status 200
                     :headers {:content-type "image/svg+xml;charset=utf-8"
                               :cache-control #"public,max-age="}
                     :body (m/via #(-> ^String %
                                       Jsoup/parse
                                       (.selectFirst (str "text:contains(" test-project-version ")"))
                                       .text)
                                  (m/equals test-project-version))}
                    (pdt/response-for *connector* :get (str "/badge/" test-project)))))

    (t/testing "searchset api (used by cljdoc)"
      (t/is (match? {:status 200
                     :headers {:content-type "application/json"}
                     :body (m/via json/parse-string {"namespaces" (m/via count 6)})}
                    (pdt/response-for *connector* :get (str "/api/searchset/" test-project "/" test-project-version)))
            "searchset API for built lib")

      (t/is (match? {:status 200
                     :headers {:content-type "application/json"}
                     :body (m/via json/parse-string {"namespaces" (m/via count 6)})}
                    (pdt/response-for *connector*
                                      :get (str "/api/searchset/" test-project "/" test-project-version)
                                      :headers {"Accept" "text/html"}))
            "searchset API for built lib disregards Accept header and always returns json"))

    (t/testing "versions api for built artifacts"
      (t/is (match? {:status 200
                     :headers {:content-type "application/json"}
                     :body (m/via json/parse-string {test-group-id {test-artifact-id [test-project-version]}})}
                    (pdt/response-for *connector*
                                      :get (str "/versions/" test-project)
                                      :headers {"Accept" "application/json"}))
            "version for artifact API as json")

      (t/is (match? {:status 200
                     :headers {:content-type "application/edn"}
                     :body (m/via edn/read-string {test-group-id {test-artifact-id [test-project-version]}})}
                    (pdt/response-for *connector*
                                      :get (str "/versions/" test-project)
                                      :headers {"Accept" "application/edn"}))
            "versions for artifact API as edn"))

    (t/testing "offline download (used by Dash)"
      (let [expected-name (str test-artifact-id "-" test-project-version)]
        (t/is (match? {:status 200
                       :headers {:content-disposition (format "attachment; filename=\"%s.zip\""
                                                              expected-name)
                                 :content-type "application/zip"}
                       :zip-entries (m/embeds [(str expected-name "/assets/js/index.js")
                                               (str expected-name "/index.html")
                                               (str expected-name "/doc/document-tests.html")
                                               (str expected-name "/api/cljdoc-exerciser.core.html")])}
                      (let [{:keys [body] :as resp} (pdt/response-for *connector*
                                                                      :get (str "/download/" test-project "/" test-project-version)
                                                                      :as :stream)]
                        (-> resp
                            (assoc :zip-entries (zip-entries body))
                            ;; don't want to dump a byte stream body in failed test
                            (dissoc :body))))))))

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
                    :body (codec/form-encode {:project "org.cljdoc/cljdoc-exerciser" :version "1.0.119"})
                    :headers {"content-type" "application/x-www-form-urlencoded"})

  (pdt/response-for *connector* :get "/sitemap.xml")
  ;; => {:status 200,
  ;;     :headers
  ;;     {:x-xss-protection "1; mode=block",
  ;;      :x-content-type-options "nosniff",
  ;;      :x-permitted-cross-domain-policies "none",
  ;;      :x-download-options "noopen",
  ;;      :cache-control "max-age=31536000,immutable,public",
  ;;      :x-frame-options "DENY",
  ;;      :strict-transport-security "max-age=31536000; includeSubdomains",
  ;;      :content-security-policy "object-src 'none'",
  ;;      :content-type "text/xml"},
  ;;     :body
  ;;     "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\"><url><loc>https://cljdoc.org/d/org.cljdoc/cljdoc-exerciser/1.0.119</loc></url></urlset>"}

  (pdt/response-for *connector*
                    :get "/versions/org.cljdoc/cljdoc-exerciser"
                    :headers {"Accept" "application/json"})
  ;; => {:status 200,
  ;;     :headers
  ;;     {:x-xss-protection "1; mode=block",
  ;;      :x-content-type-options "nosniff",
  ;;      :x-permitted-cross-domain-policies "none",
  ;;      :x-download-options "noopen",
  ;;      :cache-control "no-cache",
  ;;      :x-frame-options "DENY",
  ;;      :strict-transport-security "max-age=31536000; includeSubdomains",
  ;;      :content-security-policy "object-src 'none'",
  ;;      :content-type "application/json"},
  ;;     :body "{\"org.cljdoc\":{\"cljdoc-exerciser\":[\"1.0.119\"]}}"}

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
                        :get "/download/org.cljdoc/cljdoc-exerciser/1.0.119"
                        :as :stream)
      :body
      zip-entries)
  ;; => Aug 28, 2025 11:29:58 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/document.rb content
  ;;    INFO: possible invalid reference: custom-themes
  ;;    Aug 28, 2025 11:29:58 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/converter/html5.rb convert_embedded
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Aug 28, 2025 11:29:58 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/converter/html5.rb convert_embedded
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Aug 28, 2025 11:29:58 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/converter/html5.rb convert_embedded
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    Aug 28, 2025 11:29:58 P.M. uri:classloader:/gems/asciidoctor-2.0.23/lib/asciidoctor/converter/html5.rb convert_embedded
  ;;    INFO: possible invalid reference: catch-a-missing-or-undefined-attribute
  ;;    ["cljdoc-exerciser-1.0.119/assets/cljdoc.css"
  ;;     "cljdoc-exerciser-1.0.119/assets/js/index.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/static/codeberg.svg"
  ;;     "cljdoc-exerciser-1.0.119/assets/static/sourcehut.svg"
  ;;     "cljdoc-exerciser-1.0.119/assets/highlightjs/highlight.min.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/highlightjs/languages/clojure.min.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/highlightjs/languages/clojure-repl.min.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/highlightjs/languages/asciidoc.min.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/highlightjs/languages/groovy.min.js"
  ;;     "cljdoc-exerciser-1.0.119/index.html"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/MathJax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/config/TeX-MML-AM_CHTML.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/AssistiveMML.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/HelpDialog.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/MathEvents.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/MathMenu.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/MathZoom.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/TeX/AMSmath.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/TeX/AMSsymbols.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/TeX/noErrors.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/TeX/noUndefined.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/a11y/accessibility-menu.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/asciimath2jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/fast-preview.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/mml2jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/tex2jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/extensions/toMathML.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_AMS-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Caligraphic-Bold.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Caligraphic-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Fraktur-Bold.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Fraktur-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Main-Bold.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Main-Italic.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Main-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Math-BoldItalic.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Math-Italic.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Math-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_SansSerif-Bold.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_SansSerif-Italic.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_SansSerif-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Script-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Size1-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Size2-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Size3-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Size4-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Typewriter-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Vector-Bold.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/fonts/HTML-CSS/TeX/woff/MathJax_Vector-Regular.woff"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/element/mml/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/AsciiMath/config.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/AsciiMath/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/MathML/config.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/MathML/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/TeX/config.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/input/TeX/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/annotation-xml.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/maction.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/menclose.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/mglyph.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/mmultiscripts.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/ms.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/mtable.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/autoload/multiline.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/config.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/AMS-Regular.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Caligraphic-Bold.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Fraktur-Bold.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Fraktur-Regular.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Main-Bold.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Math-BoldItalic.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/SansSerif-Bold.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/SansSerif-Italic.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/SansSerif-Regular.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Script-Regular.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/Typewriter-Regular.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/fontdata-extra.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/fonts/TeX/fontdata.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/CommonHTML/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/PreviewHTML/config.js"
  ;;     "cljdoc-exerciser-1.0.119/assets/mathjax/jax/output/PreviewHTML/jax.js"
  ;;     "cljdoc-exerciser-1.0.119/doc/readme.html"
  ;;     "cljdoc-exerciser-1.0.119/doc/document-tests.html"
  ;;     "cljdoc-exerciser-1.0.119/doc/document-tests-asciidoctor-features.html"
  ;;     "cljdoc-exerciser-1.0.119/doc/document-tests-commonmark-features.html"
  ;;     "cljdoc-exerciser-1.0.119/doc/document-tests-asciidoctor-user-manual.html"
  ;;     "cljdoc-exerciser-1.0.119/api/cljdoc-exerciser.core.html"
  ;;     "cljdoc-exerciser-1.0.119/api/cljdoc-exerciser.ns2.html"
  ;;     "cljdoc-exerciser-1.0.119/api/cljdoc-exerciser.ns3.html"
  ;;     "cljdoc-exerciser-1.0.119/api/cljdoc-exerciser.platform.html"]

  (pdt/response-for *connector* :get "/download/nope/nope/nope")
  ;; => {:status 404,
  ;;     :headers
  ;;     {:x-xss-protection "1; mode=block",
  ;;      :x-content-type-options "nosniff",
  ;;      :x-permitted-cross-domain-policies "none",
  ;;      :x-download-options "noopen",
  ;;      :cache-control "no-cache",
  ;;      :x-frame-options "DENY",
  ;;      :strict-transport-security "max-age=31536000; includeSubdomains",
  ;;      :content-security-policy "object-src 'none'",
  ;;      :content-type "text/html"},
  ;;     :body "Could not find data, please request a build first"}

  (pdt/response-for *connector* :get "/badge/org.cljdoc/cljdoc-exerciser")
  ;; => {:status 200,
  ;;     :headers
  ;;     {:x-xss-protection "1; mode=block",
  ;;      :x-content-type-options "nosniff",
  ;;      :x-permitted-cross-domain-policies "none",
  ;;      :x-download-options "noopen",
  ;;      :cache-control "public,max-age=1800",
  ;;      :x-frame-options "DENY",
  ;;      :strict-transport-security "max-age=31536000; includeSubdomains",
  ;;      :content-security-policy "object-src 'none'",
  ;;      :content-type "image/svg+xml;charset=utf-8"},
  ;;     :body
  ;;     "<svg aria-label=\"cljdoc: 1.0.119\" height=\"20\" role=\"img\" viewBox=\"0 0 948 200\" width=\"94.8\" xmlns=\"http://www.w3.org/2000/svg\"><title>cljdoc: 1.0.119</title><linearGradient id=\"gradid\" x2=\"0\" y2=\"100%\"><stop offset=\"0\" stop-color=\"#EEE\" stop-opacity=\".1\"></stop><stop offset=\"1\" stop-opacity=\".1\"></stop></linearGradient><mask id=\"maskid\"><rect fill=\"#FFF\" height=\"200\" rx=\"30\" width=\"948\"></rect></mask><g mask=\"url(#maskid)\"><rect fill=\"#555\" height=\"200\" width=\"418\" x=\"0\"></rect><rect fill=\"#08C\" height=\"200\" width=\"530\" x=\"418\"></rect><rect fill=\"url(#gradid)\" height=\"200\" width=\"948\"></rect></g><g aria-hidden=\"aria-hidden\" fill=\"#fff\" font-family=\"Verdana,DejaVu Sans,sans-serif\" font-size=\"110\" text-anchor=\"start\"><text fill=\"#000\" opacity=\"0.25\" textLength=\"318\" x=\"60\" y=\"148\">cljdoc</text><text textLength=\"318\" x=\"50\" y=\"138\">cljdoc</text><text fill=\"#000\" opacity=\"0.25\" textLength=\"430\" x=\"473\" y=\"148\">1.0.119</text><text textLength=\"430\" x=\"463\" y=\"138\">1.0.119</text></g></svg>"}

  ;; call this when done to shutdown and clean up test dir
  (halt! s)

  :eoc)

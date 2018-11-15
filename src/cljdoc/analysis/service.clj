(ns cljdoc.analysis.service
  (:require [clj-http.lite.client :as http]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.java.shell :as sh]
            [cljdoc.util :as util]))

(defprotocol IAnalysisService
  "Services that can run analysis of Clojure code for us

  Services that implement this interface will receive all relevant information
  via their `trigger-build` method. The expectation then is that the services
  will eventually call `/api/ingest-api` with the appropriate params.

  Initially this has been done with CircleCI but this is tricky during local
  development (webhooks and all). A local service is now implemented for this
  purpose."
  (trigger-build [_ {:keys [build-id project version jarpath pompath]}]))

(defrecord CircleCI [api-token builder-project analyzer-version]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
               {:accept "application/json"
                ;; https://github.com/hiredman/clj-http-lite/issues/15
                :form-params {"build_parameters[CLJDOC_ANALYZER_VERSION]" analyzer-version
                              "build_parameters[CLJDOC_BUILD_ID]" build-id
                              "build_parameters[CLJDOC_PROJECT]" project
                              "build_parameters[CLJDOC_PROJECT_VERSION]" version
                              "build_parameters[CLJDOC_PROJECT_JAR]" jarpath
                              "build_parameters[CLJDOC_PROJECT_POM]" pompath}
                :basic-auth [api-token ""]})))

(defn circle-ci
  [{:keys [api-token builder-project analyzer-version]}]
  (assert (seq api-token) "blank or nil api-token passed to CircleCI component")
  (assert (seq builder-project) "blank or nil builder-project passed to CircleCI component")
  (assert (seq analyzer-version) "blank or nil analyzer-version passed to CircleCI component")
  (->CircleCI api-token builder-project analyzer-version))

(defn circle-ci? [x]
  (instance? CircleCI x))

(defn get-circle-ci-build-artifacts
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num "/artifacts?circle-token=:token")
   {:accept "application/json"
    :basic-auth [(:api-token circle-ci) ""]}))

(defn run-analyze-script
  "Run ./script/analyze.sh and return the path to the file containing
  analysis results. This is also the script that is used in the \"production\"
  [cljdoc-builder project](https://github.com/martinklepsch/cljdoc-builder)"
  [project version jarpath pompath]
  (let [args ["./script/analyze.sh" project version jarpath pompath]]
    (when-not (zero? (:exit (apply sh/sh args)))
      (throw (Exception. (str "Error running: " (string/join " " args)))))
    (str util/analysis-output-prefix (util/cljdoc-edn project version))))

(defrecord Local [ingest-api-url]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (future
      (try
        (log/infof "Starting local analysis for %s %s %s" project version jarpath)
        (let [cljdoc-edn-file (run-analyze-script project version jarpath pompath)]
          (log/infof "Got file from Local AnalysisService %s" cljdoc-edn-file)
          (log/info "Posting to" ingest-api-url)
          (http/post ingest-api-url
                     {:form-params {:project project
                                    :version version
                                    :build-id build-id
                                    :cljdoc-edn cljdoc-edn-file}
                      :content-type "application/x-www-form-urlencoded"
                      :basic-auth ["cljdoc" "cljdoc"]}))
        (catch Throwable t
          (log/errorf t "Exception while analyzing %s %s, see cljdoc.analysis.service for help" project version))))))

(comment
  (def r
    (let [b "http://repo.clojars.org/speculative/speculative/0.0.2/speculative-0.0.2"]
      (sh/sh "./script/analyze.sh" "speculative"  "0.0.2" (str b ".jar") (str b ".pom"))))
  )

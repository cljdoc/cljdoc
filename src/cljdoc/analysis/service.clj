(ns cljdoc.analysis.service
  (:require [clj-http.lite.client :as http]
            [clojure.tools.logging :as log]
            [cljdoc.analysis.runner :as analysis]))

(defprotocol IAnalysisService
  "Services that can run analysis of Clojure code for us

  Services that implement this interface will receive all relevant information
  via their `trigger-build` method. The expectation then is that the services
  will eventually call `/api/full-build` with the appropriate params.

  Initially this has been done with CircleCI but this is tricky during local
  development (webhooks and all). A local service is now implemented for this
  purpose."
  (trigger-build [_ {:keys [build-id project version jarpath pompath]}]))

(defrecord CircleCI [api-token builder-project]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
               {:accept "application/json"
                ;; https://github.com/hiredman/clj-http-lite/issues/15
                :form-params {"build_parameters[CLJDOC_BUILD_ID]" build-id
                              "build_parameters[CLJDOC_PROJECT]" project
                              "build_parameters[CLJDOC_PROJECT_VERSION]" version
                              "build_parameters[CLJDOC_PROJECT_JAR]" jarpath
                              "build_parameters[CLJDOC_PROJECT_POM]" pompath}
                :basic-auth [api-token ""]})))

(defn circle-ci
  [api-token builder-project]
  (assert (seq api-token) "blank or nil api-token passed to CircleCI component")
  (assert (seq builder-project) "blank or nil builder-project passed to CircleCI component")
  (->CircleCI api-token builder-project))

(defn circle-ci? [x]
  (instance? CircleCI x))

(defn get-circle-ci-build-artifacts
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num "/artifacts?circle-token=:token")
   {:accept "application/json"
    :basic-auth [(:api-token circle-ci) ""]}))

(defrecord Local [full-build-url]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (future
      (try
        (log/infof "Starting local analysis for %s %s %s" project version jarpath)
        (let [cljdoc-edn-file (analysis/analyze-impl (symbol project) version jarpath pompath)]
          (log/infof "Got file from Local AnalysisService %s" cljdoc-edn-file)
          (log/info "Posting to" full-build-url)
          (http/post full-build-url
                     {:form-params {:project project
                                    :version version
                                    :build-id build-id
                                    :cljdoc-edn (.getPath cljdoc-edn-file)}
                      :content-type "application/x-www-form-urlencoded"
                      :basic-auth ["cljdoc" "cljdoc"]}))
        (catch Throwable t
          (log/errorf t "Exception while analyzing %s %s" project version))))))

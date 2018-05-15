(ns cljdoc.analysis.service
  (:require [aleph.http]))

(defprotocol IAnalysisService
  "Services that can run analysis of Clojure code for us

  Services that implement this interface will receive all relevant information
  via their `trigger-build` method. The expectation then is that the services
  will eventually call `/api/full-build` with the appropriate params.
  
  Initially this has been done with CircleCI but this is tricky during local
  development (webhooks and all). A local service is now implemented for this
  purpose."
  (trigger-build [_ {:keys [build-id project version jarpath]}]))

(defrecord CircleCI [api-token builder-project]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath]}]
    {:pre [(string? build-id) (string? project) (string? version) (string? jarpath)]}
    @(aleph.http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
                      {:accept "application/json"
                       :form-params {"build_parameters" {"CLJDOC_BUILD_ID" build-id
                                                         "CLJDOC_PROJECT" project
                                                         "CLJDOC_PROJECT_VERSION" version
                                                         "CLJDOC_PROJECT_JAR" jarpath}}
                       :basic-auth [api-token ""]})))

(defn circle-ci
  [api-token builder-project]
  (assert (seq api-token) "blank or nil api-token passed to CircleCI component")
  (assert (seq builder-project) "blank or nil builder-project passed to CircleCI component")
  (->CircleCI api-token builder-project))

(defn get-circle-ci-build-artifacts
  [circle-ci build-num]
  (assert (instance? CircleCI circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  @(aleph.http/get
    (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num "/artifacts?circle-token=:token")
    {:accept "application/json"
     :basic-auth [(:api-token circle-ci) ""]}))

(defrecord Local []
  IAnalysisService
  (trigger-build [_ {:keys [build-id project version jarpath]}]
    (assert false)))

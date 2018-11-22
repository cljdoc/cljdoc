(ns cljdoc.analysis.service
  (:require [clj-http.lite.client :as http]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clojure.java.shell :as sh]
            [cheshire.core :as json]
            [cljdoc.util :as util]))

(defprotocol IAnalysisService
  "Services that can run analysis of Clojure code for us

  Services that implement this interface will receive all relevant information
  via their `trigger-build` method. The result of `trigger-build` can then be
  passed to `wait-for-build` which will block until the the build finished."
  (trigger-build [_ {:keys [build-id project version jarpath pompath]}])
  (wait-for-build [_ build-info]))

;; CircleCI AnalysisService -----------------------------------------------------

(declare get-circle-ci-build-artifacts get-circle-ci-build poll-circle-ci-build)

(defrecord CircleCI [api-token builder-project analyzer-version]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (log/infof "Starting CircleCI analysis for %s %s %s" project version jarpath)
    (let [build (http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
                           {:accept "application/json"
                            ;; https://github.com/hiredman/clj-http-lite/issues/15
                            :form-params {"build_parameters[CLJDOC_ANALYZER_VERSION]" analyzer-version
                                          "build_parameters[CLJDOC_BUILD_ID]" build-id
                                          "build_parameters[CLJDOC_PROJECT]" project
                                          "build_parameters[CLJDOC_PROJECT_VERSION]" version
                                          "build_parameters[CLJDOC_PROJECT_JAR]" jarpath
                                          "build_parameters[CLJDOC_PROJECT_POM]" pompath}
                            :basic-auth [api-token ""]})
          build-data (-> build :body json/parse-string)]
      {:build-num (get build-data "build_num")
       :build-url (get build-data "build_url")
       :project   project
       :version   version}))
  (wait-for-build
    [this {:keys [project version build-num]}]
    (assert (string? project))
    (assert (string? version))
    (assert (integer? build-num))
    (let [done-build (poll-circle-ci-build this build-num)
          success?   (contains? #{"success" "fixed"} (get done-build "status"))
          cljdoc-edn (cljdoc.util/cljdoc-edn project version)]
      (let [artifacts (-> (get-circle-ci-build-artifacts this build-num)
                          :body json/parse-string)]
        (if-let [artifact (and success?
                               (= 1 (count artifacts))
                               (= cljdoc-edn (get (first artifacts) "path"))
                               (first artifacts))]
          {:analysis-result (get artifact "url")}
          (throw (ex-info "Analysis on CircleCI failed"
                          {:service :circle-ci, :build done-build})))))))

(defn circle-ci
  [{:keys [api-token builder-project analyzer-version]}]
  (assert (seq api-token) "blank or nil api-token passed to CircleCI component")
  (assert (seq builder-project) "blank or nil builder-project passed to CircleCI component")
  (assert (seq analyzer-version) "blank or nil analyzer-version passed to CircleCI component")
  (->CircleCI api-token builder-project analyzer-version))

(defn circle-ci? [x]
  (instance? CircleCI x))

(defn- get-circle-ci-build-artifacts
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num "/artifacts")
   {:accept "application/json", :basic-auth [(:api-token circle-ci) ""]}))

(defn- get-circle-ci-build
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num)
   {:accept "application/json", :basic-auth [(:api-token circle-ci) ""]}))

(defn- poll-circle-ci-build [circle-ci build-num]
  (loop [n 60] ; 60 * 5s = 5min
    (log/info "CircleCI: Polling build" build-num)
    (let [build (-> (get-circle-ci-build circle-ci build-num)
                    :body json/parse-string)]
      (cond
        (= "finished" (get build "lifecycle"))
        build

        (pos? n)
        (do (Thread/sleep 5000)
            (recur (dec n)))

        :else
        (throw (ex-info "Build timeout" {:build-num build-num}))))))

;; Local Analysis Service -------------------------------------------------------

(defrecord Local []
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath]}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (future
      (log/infof "Starting local analysis for %s %s %s" project version jarpath)
      ;; Run ./script/analyze.sh and return the path to the file containing
      ;; analysis results. This is also the script that is used in the "production"
      ;; [cljdoc-builder project](https://github.com/martinklepsch/cljdoc-builder)
      (let [proc            (apply sh/sh ["./script/analyze.sh" project version jarpath pompath])
            cljdoc-edn-file (str util/analysis-output-prefix (util/cljdoc-edn project version))]
        {:analysis-result cljdoc-edn-file
         :proc proc})))
  (wait-for-build
    [_ build-future]
    (let [{:keys [analysis-result proc]} @build-future]
      (log/infof "Got file from Local AnalysisService %s" analysis-result)
      (if (zero? (:exit proc))
        {:analysis-result analysis-result}
        (throw (ex-info "Analysis with local AnalysisService failed"
                        {:service :local, :proc proc}))))))

(comment
  (def r
    (let [b "http://repo.clojars.org/speculative/speculative/0.0.2/speculative-0.0.2"]
      (sh/sh "./script/analyze.sh" "speculative"  "0.0.2" (str b ".jar") (str b ".pom"))))
  )

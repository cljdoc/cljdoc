(ns cljdoc.analysis.service
  (:require [clj-http.lite.client :as http]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [cheshire.core :as json]
            [cljdoc.git-repo :as git-repo]
            [cljdoc-shared.analysis :as analysis]))

(defprotocol IAnalysisService
  "Services that can run analysis of Clojure code for us

  Services that implement this interface will receive all relevant information
  via their `trigger-build` method. The result of `trigger-build` can then be
  passed to `wait-for-build` which will block until the the build finished."
  (trigger-build [_ {:keys [build-id project version jarpath pompath]}])
  (wait-for-build [_ build-info]))

(defn- ng-analysis-args
  "Previously all analysis parameters were passed as positional arguments to a tiny shell
  script around `clojure`. Supplying more complex parameters this way quickly becomes cumbersome
  and error prone. For this reason the `ng` analysis code always receives only a single
  argument which is expected to be an EDN map (encoded as string)

  See cljdoc-analyzer project for more details."
  [trigger-build-arg repos]
  (assert (:project trigger-build-arg))
  (assert (:version trigger-build-arg))
  (assert (:jarpath trigger-build-arg))
  (assert (:pompath trigger-build-arg))
  (assoc (select-keys trigger-build-arg [:project :version :jarpath :pompath :languages])
         :repos repos))

(defn- get-analyzer-dep []
  (let [url "https://github.com/cljdoc/cljdoc-analyzer.git"
        sha (git-repo/ls-remote-sha url "RELEASE")]
    ;; temporarily override for dev testing or to force a specific sha as needed
    {:deps {'cljdoc/cljdoc-analyzer {:git/url url :sha sha}}
     :version sha}))

;; CircleCI AnalysisService -----------------------------------------------------

(declare get-circle-ci-build-artifacts get-circle-ci-build poll-circle-ci-build)

(defrecord CircleCI [api-token builder-project repos]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath] :as arg}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (log/infof "Starting CircleCI analysis for %s %s %s" project version jarpath)
    (let [analyzer-dep (get-analyzer-dep)
          build (http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
                           {:accept "application/json"
                            :form-params {"build_parameters[CLJDOC_ANALYZER_DEP]" (pr-str (select-keys analyzer-dep [:deps]))
                                          "build_parameters[CLJDOC_BUILD_ID]" build-id
                                          "build_parameters[CLJDOC_ANALYZER_ARGS]" (pr-str (ng-analysis-args arg repos))}
                            :basic-auth [api-token ""]})
          build-data (-> build :body json/parse-string)]
      {:build-num (get build-data "build_num")
       :build-url (get build-data "build_url")
       :project   project
       :version   version
       :analyzer-version (:version analyzer-dep)}))
  (wait-for-build
    [this {:keys [project version build-num]}]
    (assert (string? project))
    (assert (string? version))
    (assert (integer? build-num))
    (let [done-build (poll-circle-ci-build this build-num)
          success?   (contains? #{"success" "fixed"} (get done-build "status"))
          cljdoc-analysis-edn (analysis/result-file project version)
          artifacts (-> (get-circle-ci-build-artifacts this build-num)
                        :body json/parse-string)]
      (if-let [artifact (and success?
                             (= 1 (count artifacts))
                             (= cljdoc-analysis-edn (get (first artifacts) "path"))
                             (first artifacts))]
        {:analysis-result (get artifact "url")}
        (throw (ex-info "Analysis on CircleCI failed"
                        {:service :circle-ci, :build done-build}))))))

(defn circle-ci
  [{:keys [api-token builder-project] :as args}]
  (assert (seq api-token) "blank or nil api-token passed to CircleCI component")
  (assert (seq builder-project) "blank or nil builder-project passed to CircleCI component")
  (map->CircleCI args))

(defn circle-ci? [x]
  (instance? CircleCI x))

(defn- get-circle-ci-build-artifacts
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num "/artifacts")
   {:accept "application/json", :basic-auth [(:api-token circle-ci) ""]}))

(defn- get-circle-ci-build
  "Retrieve information about the build identified by `build-num`. The project
  is retrieved from the `circle-ci` AnalysisService instance.

  Also see: https://circleci.com/docs/api/v1-reference/#build"
  [circle-ci build-num]
  (assert (circle-ci? circle-ci) (format "not a CircleCI instance: %s" circle-ci))
  (http/get
   (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci) "/" build-num)
   {:accept "application/json", :basic-auth [(:api-token circle-ci) ""]}))

(defn- poll-circle-ci-build [circle-ci build-num]
  (loop [n 120] ; 120 * 5s = 10min
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
        (throw (ex-info "Build timeout" {:cljdoc/error "analysis-job-timeout"
                                         :build-num build-num}))))))

;; Local Analysis Service -------------------------------------------------------

(defrecord Local [repos]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath] :as arg}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (log/infof "Starting local analysis for %s %s %s" project version jarpath)
      ;; Run the analysis-runner (yeah) and return the path to the file containing
      ;; analysis results. This mimics production usage in
      ;; https://github.com/cljdoc/builder circleci config.
    (let [analyzer-dep (get-analyzer-dep)
          future-proc (future
                        (sh/sh "clojure" "-Sdeps" (pr-str (select-keys analyzer-dep [:deps]))
                               "-M" "-m" "cljdoc-analyzer.cljdoc-main" (pr-str (ng-analysis-args arg repos))
                               :dir (doto (io/file "/tmp/cljdoc-analysis-runner-dir/") (.mkdir))))
          cljdoc-analysis-edn-file (analysis/result-path project version)]
      {:analysis-result cljdoc-analysis-edn-file
       :analyzer-version (:version analyzer-dep)
       :future-proc future-proc}))
  (wait-for-build
    [_ {:keys [analysis-result future-proc]}]
    (let [proc @future-proc]
      (log/infof "Got file from Local AnalysisService %s" analysis-result)
      (if (zero? (:exit proc))
        {:analysis-result analysis-result}
        (do
          (println "ERROR Analysis with local AnalysisService failed")
          (println "STDOUT -----------------------------------------------------")
          (println (:out proc))
          (println "STDERR -----------------------------------------------------")
          (println (:err proc))
          (throw (ex-info "Analysis with local AnalysisService failed"
                          {:service :local, :proc proc})))))))

(comment

  (def p-succeed
    {:project "bidi"
     :version "2.1.3"
     :jarpath "http://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.jar"
     :pompath "http://repo.clojars.org/bidi/bidi/2.1.3/bidi-2.1.3.pom"})

  (def p-fail
    {:project "com.lemondronor/ads-b"
     :version "0.1.3"
     :jarpath "https://repo.clojars.org/com/lemondronor/ads-b/0.1.3/ads-b-0.1.3.jar"
     :pompath "https://repo.clojars.org/com/lemondronor/ads-b/0.1.3/ads-b-0.1.3.pom"})

  (let [service (or #_(circle-ci (cljdoc.config/circle-ci))
                 (->Local))
        build   (trigger-build service p-fail)]
    (wait-for-build service build)))

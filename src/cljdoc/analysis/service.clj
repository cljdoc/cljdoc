(ns cljdoc.analysis.service
  (:require [clj-http.lite.client :as http]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
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

(defn- ng-analysis-args
  "Previously all analysis parameters were passed as positional arguments to a tiny shell
  script around `clojure`. Supplying more complex parameters this way quickly becomes cumbersome
  and error prone. For this reason the `ng` analysis code always receives only a single
  argument which is expected to be an EDN map (encoded as string)

  See [[cljdoc.analysis.runner-ng]] for more details."
  [trigger-build-arg repos]
  ;; TODO add a spec for this
  (assert (:project trigger-build-arg))
  (assert (:version trigger-build-arg))
  (assert (:jarpath trigger-build-arg))
  (assert (:pompath trigger-build-arg))
  {:project (:project trigger-build-arg)
   :version (:version trigger-build-arg)
   :jarpath (:jarpath trigger-build-arg)
   :pompath (:pompath trigger-build-arg)
   :repos   repos})

(def analyzer-version
  "02d0ca4b982c60a5920cf391b3eb8280b4aa97dd")

(def analyzer-dependency
  {:deps {'cljdoc-analyzer {:git/url "https://github.com/lread/cljdoc-analyzer.git"
                            :sha analyzer-version}}})


;; CircleCI AnalysisService -----------------------------------------------------

(declare get-circle-ci-build-artifacts get-circle-ci-build poll-circle-ci-build)

(defrecord CircleCI [api-token builder-project repos]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath] :as arg}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (log/infof "Starting CircleCI analysis for %s %s %s" project version jarpath)
    (let [build (http/post (str "https://circleci.com/api/v1.1/project/" builder-project "/tree/master")
                           {:accept "application/json"
                            ;; https://github.com/hiredman/clj-http-lite/issues/15
                            :form-params {"build_parameters[CLJDOC_ANALYZER_DEP]" (pr-str analyzer-dependency)
                                          "build_parameters[CLJDOC_BUILD_ID]" build-id
                                          "build_parameters[CLJDOC_ANALYZER_ARGS]" (pr-str (ng-analysis-args arg repos))}
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
          cljdoc-edn (cljdoc.util/cljdoc-edn project version)
          artifacts (-> (get-circle-ci-build-artifacts this build-num)
                        :body json/parse-string)]
        (if-let [artifact (and success?
                               (= 1 (count artifacts))
                               (= cljdoc-edn (get (first artifacts) "path"))
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

(defrecord Local [repos]
  IAnalysisService
  (trigger-build
    [_ {:keys [build-id project version jarpath pompath] :as arg}]
    {:pre [(int? build-id) (string? project) (string? version) (string? jarpath) (string? pompath)]}
    (future
      (log/infof "Starting local analysis for %s %s %s" project version jarpath)
      ;; Run the analysis-runner (yeah) and return the path to the file containing
      ;; analysis results. This is also the script that is used in the "production"
      ;; [cljdoc-builder project](https://github.com/martinklepsch/cljdoc-builder)
      (let [proc            (sh/sh "clojure" "-Sdeps" (pr-str analyzer-dependency)
                                   "-m" "cljdoc-analyzer.cljdoc-main" (pr-str (ng-analysis-args arg repos))
                                   :dir (doto (io/file "/tmp/cljdoc-analysis-runner-dir/") (.mkdir)))
            cljdoc-edn-file (str util/analysis-output-prefix (util/cljdoc-edn project version))]
        {:analysis-result cljdoc-edn-file
         :proc proc})))
  (wait-for-build
    [_ build-future]
    (let [{:keys [analysis-result proc]} @build-future]
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
    (wait-for-build service build))

  )

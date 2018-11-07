(ns cljdoc.server.api
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util :as util]
            [cljdoc.util.repositories :as repositories]
            [cljdoc.config] ; should not be necessary but instead be passed as args
            [cljdoc.renderers.html :as html]
            [clojure.tools.logging :as log]
            [clj-http.lite.client :as http]
            [cheshire.core :as json]))


;; Circle CI API stuff -----------------------------------------------
;; TODO move into separate namespace and maybe record later

(defn get-build [circle-ci-config build-num]
  (http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num)
            {:accept "application/json"
             :basic-auth [(:api-token circle-ci-config) ""]}))

;; cljdoc API client functions ---------------------------------------

(defn post-api-data-via-http [params]
  (http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/ingest-api")
             {:throw-exceptions false
              :form-params params
              :content-type "application/x-www-form-urlencoded"
              :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body json/parse-string)]
    (http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/hooks/circle-ci")
               {:body (json/generate-string {"payload" payload})
                :content-type "application/json"})))

(defn kick-off-build!
  "Run the Git analysis for the provided `project` and kick of an
  analysis build for `project` using the provided `analysis-service`."
  [{:keys [storage build-tracker analysis-service] :as deps}
   {:keys [project version] :as project}]
  (let [a-uris    (repositories/artifact-uris project version)
        build-id  (build-log/analysis-requested!
                   build-tracker
                   (cljdoc.util/group-id project)
                   (cljdoc.util/artifact-id project)
                   version)
        kick-off-job! (fn kick-off-job! []
                        ;; More work is TBD here in order to pass the configuration
                        ;; received from a users Git repository into the analysis service
                        ;; https://github.com/cljdoc/cljdoc/issues/107
                        (let [ana-resp (analysis-service/trigger-build
                                        analysis-service
                                        {:project project
                                         :version version
                                         :jarpath (:jar a-uris)
                                         :pompath (:pom a-uris)
                                         :build-id build-id})]
                          (if (analysis-service/circle-ci? analysis-service)
                            (let [build-num (-> ana-resp :body json/parse-string (get "build_num"))
                                  job-url   (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)
                                  ana-v     (:analyzer-version analysis-service)]
                              (when (= 201 (:status ana-resp))
                                (assert build-num "build number missing from CircleCI response")
                                (build-log/analysis-kicked-off! build-tracker build-id job-url ana-v)
                                (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}" build-id job-url)))
                              (build-log/analysis-kicked-off! build-tracker build-id nil nil))))]
    (future
      (try
        (if-let [scm-info (ingest/scm-info project (slurp (:pom a-uris)))]
          (let [{:keys [error scm-url commit] :as git-result}
                (ingest/ingest-git! storage {:project project
                                             :version version
                                             :scm-url (:url scm-info)
                                             :pom-revision (:sha scm-info)})]
            (when error (log/warnf "Error while processing %s %s: %s" project version error))
            (build-log/git-completed! build-tracker build-id (update git-result :error :type))
            (kick-off-job!))
          (kick-off-job!))
        (catch Throwable e
          ;; TODO store in column for internal exception
          (log/error e (format "Exception while processing %s %s (build %s)" project version build-id))
          (build-log/failed! build-tracker build-id "exception-during-import")
          (throw e))))

    build-id))

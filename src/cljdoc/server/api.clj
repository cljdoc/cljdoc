(ns cljdoc.server.api
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util]
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

(defn run-full-build [params]
  (http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/full-build")
             {:throw-exceptions false
              :form-params params
              :content-type "application/x-www-form-urlencoded"
              :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body json/parse-string)]
    (http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/hooks/circle-ci")
               {:body (json/generate-string {"payload" payload})
                :content-type "application/json"})))

(defn initiate-build
  "Kick of a build for the given project and version

  If successful, return build-id, otherwise throws."
  [{:keys [analysis-service build-tracker project version]}]
  {:pre [(some? analysis-service) (some? build-tracker)]}
  ;; WARN using `artifact-uris` here introduces some coupling to
  ;; clojars, making it a little less easy to build documentation for
  ;; artifacts only existing in local ~/.m2
  (let [a-uris    (repositories/artifact-uris project version)
        build-id  (build-log/analysis-requested!
                   build-tracker
                   (cljdoc.util/group-id project)
                   (cljdoc.util/artifact-id project)
                   version)
        ana-resp  (analysis-service/trigger-build
                   analysis-service
                   {:project project
                    :version version
                    :jarpath (:jar a-uris)
                    :pompath (:pom a-uris)
                    :build-id build-id})]
    (if (analysis-service/circle-ci? analysis-service)
      (let [build-num (-> ana-resp :body json/parse-string (get "build_num"))
            job-url   (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)]
        (when (= 201 (:status ana-resp))
          (assert build-num "build number missing from CircleCI response")
          (build-log/analysis-kicked-off! build-tracker build-id job-url)
          (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}" build-id job-url))
        build-id)
      (do
        (build-log/analysis-kicked-off! build-tracker build-id nil)
        build-id))))

(defn full-build
  [{:keys [storage build-tracker] :as deps}
   {:keys [project version build-id cljdoc-edn] :as params}]
  (assert (and project version build-id cljdoc-edn)
          (format "Params insufficient %s" params))
  (let [cljdoc-edn-contents (clojure.edn/read-string (slurp cljdoc-edn))
        build-id            (Long. build-id)]
    (build-log/analysis-received! build-tracker build-id cljdoc-edn)
    ;; TODO put this back in place
    ;; (cljdoc.util/assert-match project version cljdoc-edn)
    (try
      (ingest/ingest-cljdoc-edn storage cljdoc-edn-contents)
      (if-let [scm-info (ingest/scm-info project (:pom-str cljdoc-edn-contents))]
        (let [{:keys [error scm-url commit]} (ingest/ingest-git! storage {:project project
                                                                          :version version
                                                                          :scm-url (:url scm-info)
                                                                          :pom-revision (:sha scm-info)})]
          (if error
            (do (log/warnf "Error while processing %s %s: %s" project version error)
                (build-log/failed! build-tracker build-id (subs (str (:type error)) 1)))
            (build-log/completed! build-tracker build-id scm-url commit)))
        (build-log/completed! build-tracker build-id nil nil))
      (catch Throwable e
        (build-log/failed! build-tracker build-id "exception-during-import")
        (throw e)))))

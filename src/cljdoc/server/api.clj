(ns cljdoc.server.api
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util]
            [cljdoc.util.repositories :as repositories]
            [cljdoc.cache]
            [cljdoc.config] ; should not be necessary but instead be passed as args
            [cljdoc.renderers.html :as html]
            [clojure.tools.logging :as log]
            [aleph.http :as http]
            [byte-streams :as bs]
            [jsonista.core :as json]))


;; Circle CI API stuff -----------------------------------------------
;; TODO move into separate namespace and maybe record later

(defn get-build [circle-ci-config build-num]
  @(http/get (str "https://circleci.com/api/v1.1/project/" (:builder-project circle-ci-config) "/" build-num)
             {:accept "application/json"
              :basic-auth [(:api-token circle-ci-config) ""]}))

;; cljdoc API client functions ---------------------------------------

(defn run-full-build [params]
  @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/full-build")
              {:form-params params
               :content-type "application/x-www-form-urlencoded"
               :basic-auth ["cljdoc" "cljdoc"]})) ;TODO fix

(defn test-webhook [circle-ci-config build-num]
  (let [payload (-> (get-build circle-ci-config build-num) :body bs/to-string json/read-value)]
    @(http/post (str "http://localhost:" (get-in (cljdoc.config/config) [:cljdoc/server :port]) "/api/hooks/circle-ci")
                {:body (json/write-value-as-string {"payload" payload})
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
      (let [build-num (-> ana-resp :body bs/to-string json/read-value (get "build_num"))
            job-url   (str "https://circleci.com/gh/martinklepsch/cljdoc-builder/" build-num)]
        (telegram/build-requested project version job-url)
        (when (= 201 (:status ana-resp))
          (assert build-num "build number missing from CircleCI response")
          (build-log/analysis-kicked-off! build-tracker build-id job-url)
          (log/infof "Kicked of analysis job {:build-id %s :circle-url %s}" build-id job-url))
        build-id)
      (do
        (build-log/analysis-kicked-off! build-tracker build-id nil)
        build-id))))

(defn full-build
  [{:keys [dir build-tracker] :as deps}
   {:keys [project version build-id cljdoc-edn] :as params}]
  (let [cljdoc-edn-contents (clojure.edn/read-string (slurp cljdoc-edn))
        build-id            (Long. build-id)]
    (build-log/analysis-received! build-tracker build-id cljdoc-edn)
    ;; TODO put this back in place
    ;; (cljdoc.util/assert-match project version cljdoc-edn)
    (try
      (let [{:keys [scm-url commit]} (ingest/ingest-cljdoc-edn dir cljdoc-edn-contents)]
        (build-log/completed! build-tracker build-id scm-url commit))
      (catch Throwable e
        (build-log/failed! build-tracker build-id "exception-during-import")
        (throw e)))))

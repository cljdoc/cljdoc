(ns cljdoc.server.api
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util :as util]
            [cljdoc.util.repositories :as repositories]
            [clojure.tools.logging :as log]))

(defn analyze-and-import-api!
  [{:keys [analysis-service storage build-tracker]}
   {:keys [project version jar pom build-id]}]
  ;; More work is TBD here in order to pass the configuration
  ;; received from a users Git repository into the analysis service
  ;; https://github.com/cljdoc/cljdoc/issues/107
  (let [ana-v    analysis-service/analyzer-version
        ana-resp (analysis-service/trigger-build
                  analysis-service
                  {:project project
                   :version version
                   :jarpath jar
                   :pompath pom
                   :build-id build-id})]

    ;; `build-url` and `ana-v` are only set for CircleCI
    (build-log/analysis-kicked-off! build-tracker build-id (:build-url ana-resp) ana-v)

    (try
      (let [build-result (analysis-service/wait-for-build analysis-service ana-resp)
            file-uri     (:analysis-result build-result)
            data         (util/read-cljdoc-edn file-uri)
            ns-count     (let [{:strs [clj cljs]} (:codox data)]
                           (count (set (into (map :name cljs) (map :name clj)))))]
        (build-log/analysis-received! build-tracker build-id file-uri)
        (ingest/ingest-cljdoc-edn storage data)
        (build-log/api-imported! build-tracker build-id ns-count)
        (build-log/completed! build-tracker build-id)
        (when (zero? ns-count) (throw (Exception. "No namespaces found"))))
      (catch Exception e
        (log/errorf e "analysis job failed for project: %s, version: %s, build-id: %s" project version build-id)
        (build-log/failed! build-tracker build-id "analysis-job-failed")))))

(defn kick-off-build!
  "Run the Git analysis for the provided `project` and kick of an
  analysis build for `project` using the provided `analysis-service`.

  Optional `:jar` and `:pom` keys can be provided via the `coords` map
  to supply non-default paths like local files."
  [{:keys [storage build-tracker analysis-service] :as deps}
   {:keys [project version jar pom scm-url scm-rev] :as coords}]
  (let [a-uris    (when-not (and jar pom)
                    (repositories/artifact-uris project version))
        v-entity  (util/version-entity project version)
        build-id  (build-log/analysis-requested!
                   build-tracker (:group-id v-entity) (:artifact-id v-entity) version)
        ;; override default artifact-uris if they have been provided
        ;; as part of `coords` (nice to provide a local jar/pom)
        ana-args  (merge a-uris coords {:build-id build-id})
        pom-scm-info (ingest/scm-info (:pom ana-args))
        ;; Manually specified scm-url, scm-rev take precedence
        scm-url (or scm-url (:url pom-scm-info))
        scm-rev (or scm-rev (:sha pom-scm-info))]

    {:build-id build-id
     :future (future
               (try
                 (storage/import-doc storage (util/version-entity project version) {})
                 ;; Git analysis may derive the revision via tags but a URL is always required.
                 (if scm-url
                   (let [{:keys [error] :as git-result}
                         (ingest/ingest-git! storage {:project project
                                                      :version version
                                                      :scm-url scm-url
                                                      :pom-revision scm-rev})]
                     (when error
                       (log/warnf "Error while processing %s %s: %s" project version error))
                     (build-log/git-completed! build-tracker build-id (update git-result :error :type)))
                   (build-log/git-completed! build-tracker build-id {:error "Error while trying to process Git repository: no SCM URL found"}))
                 (analyze-and-import-api! deps ana-args)

                 (catch Throwable e
                   (log/error e (format "Exception while processing %s %s (build %s)" project version build-id))
                   (build-log/failed! build-tracker build-id "exception-during-import" e)
                   (throw e))))}))

(comment
  (-> (kick-off-build!
       {:storage (:cljdoc/storage integrant.repl.state/system)
        :analysis-service (:cljdoc/analysis-service integrant.repl.state/system)
        :build-tracker (:cljdoc/build-tracker integrant.repl.state/system)}
       {:project "bidi" :version "2.1.3"})
      :job deref)

  )

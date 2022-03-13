(ns cljdoc.server.api
  (:require [cljdoc.analysis.service :as analysis-service]
            [cljdoc.server.ingest :as ingest]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.util.repositories :as repositories]
            [cljdoc-shared.analysis-edn :as analysis-edn]
            [clojure.tools.logging :as log]
            [cljdoc.user-config :as user-config]))

(defn analyze-and-import-api!
  [{:keys [analysis-service storage build-tracker]}
   {:keys [project version jar pom languages build-id]}]
  (let [ana-resp (analysis-service/trigger-build
                  analysis-service
                  {:project project
                   :version version
                   :jarpath jar
                   :pompath pom
                   :languages languages
                   :build-id build-id})]

    ;; `build-url` is only set for CircleCI
    (build-log/analysis-kicked-off! build-tracker build-id
                                    (:build-url ana-resp)
                                    (:analyzer-version ana-resp))

    (try
      (let [build-result (analysis-service/wait-for-build analysis-service ana-resp)
            file-uri     (:analysis-result build-result)
            data         (analysis-edn/read file-uri)
            ns-count     (let [{:strs [clj cljs]} (:analysis data)]
                           (count (set (into (map :name cljs) (map :name clj)))))]
        (build-log/analysis-received! build-tracker build-id file-uri)
        (ingest/ingest-cljdoc-analysis-edn storage data)
        (build-log/api-imported! build-tracker build-id ns-count)
        (build-log/completed! build-tracker build-id))
      (catch Exception e
        (let [d (ex-data e)]
          (log/errorf e "analysis job failed for project: %s, version: %s, build-id: %s" project version build-id)
          (build-log/failed! build-tracker build-id (or (:cljdoc/error d) "analysis-job-failed")))))))

(defn kick-off-build!
  "Run the Git analysis for the provided `project` and kick off an
  analysis service build for `project` using the provided `analysis-service`.

  Optional for `coords` map to support testing:
  - `:jar` and `:pom` can supply non-default paths to local files.
  - `:scm-url` and `:scm-rev` will override `pom.xml` `<scm>` `<url>` and `<tag>`"
  [{:keys [storage build-tracker analysis-service] :as deps}
   {:keys [project version jar pom scm-url scm-rev] :as coords}]
  (let [a-uris    (when-not (and jar pom)
                    (repositories/artifact-uris project version))
        v-entity  (storage/version-entity project version)
        build-id  (build-log/analysis-requested!
                   build-tracker (:group-id v-entity) (:artifact-id v-entity) version)
        ;; override default artifact-uris if they have been provided
        ;; as part of `coords` (nice to provide a local jar/pom)
        ana-args  (merge a-uris coords {:build-id build-id})
        pom-scm-info (ingest/scm-info (:pom ana-args))
        scm-url (or scm-url (:url pom-scm-info))]

    {:build-id build-id
     :future (future
               (try
                 (storage/import-doc storage (storage/version-entity project version) {})
                 ;; Git analysis may derive the revision via tags but a URL is always required.

                 (let [{:keys [error config] :as git-result}
                       (if (not scm-url)
                         {:error "Error while trying to process Git repository: no SCM URL found"}
                         (ingest/ingest-git! storage {:project project
                                                      :version version
                                                      :scm-url (or scm-url (:url pom-scm-info))
                                                      :pom-revision (:sha pom-scm-info)
                                                      :requested-revision scm-rev}))]
                   (when error
                     (log/warnf "Error while processing %s %s: %s" project version error))
                   (build-log/git-completed! build-tracker build-id (update git-result :error :type))

                   (let [languages (user-config/languages config project)
                         ana-args (if languages (assoc ana-args :languages languages)
                                      ana-args)]
                     (analyze-and-import-api! deps ana-args)))

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
      :job deref))

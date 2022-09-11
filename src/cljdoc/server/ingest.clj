(ns cljdoc.server.ingest
  "A collection of small helpers to ingest data provided via API analysis
  or Git repositories into the database (see [[cljdoc.storage.api]])"
  (:require [cljdoc.analysis.git :as ana-git]
            [cljdoc-shared.pom :as pom]
            [cljdoc-shared.proj :as proj]
            [cljdoc.util.codox :as codox]
            [cljdoc.util.scm :as scm]
            [clojure.tools.logging :as log]
            [cljdoc.storage.api :as storage]
            [cljdoc-shared.spec.analyzer :as analyzer-spec]))

(defn ingest-cljdoc-analysis-edn
  "Store all the API-related information in the passed `cljdoc-analysis-edn` data"
  [storage {:keys [analysis group-id artifact-id version] :as cljdoc-analysis-edn}]
  (let [project (proj/clojars-id cljdoc-analysis-edn)
        artifact (storage/version-entity project version)]
    (log/info "Verifying cljdoc analysis edn contents against spec")
    (analyzer-spec/assert-result-full cljdoc-analysis-edn)
    (log/infof "Importing API into database %s %s" project version)
    (storage/import-api storage artifact (codox/sanitize-macros analysis))))

(def ^:private scm-fallback "TODO: What and why?"
  {"yada/yada" "https://github.com/juxt/yada/"})

(defn- gh-url? [s]
  (some-> s (.contains "github.com")))

(defn scm-info
  [pom-url]
  {:pre [(string? pom-url)]}
  (let [pom-doc  (pom/parse (slurp pom-url))
        artifact (:artifact-info pom-doc)
        scm-info (:scm-info pom-doc)
        project  (str (:group-id artifact) "/" (:artifact-id artifact))
        scm-url  (some-> (or (:url scm-info)
                             (when (gh-url? (:url artifact))
                               (:url artifact))
                             (scm-fallback project))
                         scm/normalize-git-url)]
    (when scm-url
      {:url scm-url
       :sha (:sha scm-info)})))

(defn ingest-git!
  "Returns map of `:scm-url` and
  - `:config` - processed user `cljdoc.edn` config
  - or `:error`
  for analysis of git repository `repo`, saves full result in `storage`"
  [storage {:keys [project version scm-url pom-revision requested-revision] :as _repo}]
  {:pre [(string? scm-url)]}
  (let [scm-rev (or requested-revision pom-revision)
        {:keys [error scm scm-articles config doc-tree]}
        (cond-> (ana-git/analyze-git-repo project version scm-url scm-rev)
          ;; The git tag expressing the project version is not relevant if git revision specifically requested
          requested-revision (update-in [:scm] dissoc :tag))]
    (if error
      {:scm-url scm-url :error error}
      (do
        (log/info "Importing Articles from" scm-url)
        (storage/import-doc
         storage
         (storage/version-entity project version)
         (cond-> {:jar          {}
                  :scm          scm
                  :config       config
                  :doc-tree     doc-tree}
           scm-articles (assoc :scm-articles scm-articles)))
        {:scm-url scm-url
         :config config}))))

(comment

  (def yada "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def bidi "/private/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-bidi-2.1.34476490973326476417/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp bidi)))

  (-> (:pom-str edn)
      (pom/parse)
      :artifact-info)

  (ingest-cljdoc-edn (io/file "data") edn))

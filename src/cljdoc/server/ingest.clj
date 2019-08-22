(ns cljdoc.server.ingest
  "A collection of small helpers to ingest data provided via API analysis
  or Git repositories into the database (see [[cljdoc.storage.api]])"
  (:require [cljdoc.util :as util]
            [cljdoc.analysis.git :as ana-git]
            [cljdoc.util.pom :as pom]
            [cljdoc.util.codox :as codox]
            [clojure.tools.logging :as log]
            [cljdoc.storage.api :as storage]
            [cljdoc.spec]))

(defn ingest-cljdoc-edn
  "Store all the API-related information in the passed `cljdoc-edn` data"
  [storage {:keys [codox group-id artifact-id version] :as cljdoc-edn}]
  (let [project (util/clojars-id cljdoc-edn)
        artifact (util/version-entity project version)]
    (log/info "Verifying cljdoc-edn contents against spec")
    (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)
    (log/infof "Importing API into database %s %s" project version)
    (storage/import-api storage artifact (codox/sanitize-macros codox))))

(defn scm-info
  [pom-url]
  {:pre [(string? pom-url)]}
  (let [pom-doc  (pom/parse (slurp pom-url))
        artifact (pom/artifact-info pom-doc)
        scm-info (pom/scm-info pom-doc)
        project  (str (:group-id artifact) "/" (:artifact-id artifact))
        scm-url  (some-> (or (:url scm-info)
                             (if (util/gh-url? (:url artifact))
                               (:url artifact))
                             (util/scm-fallback project))
                         util/normalize-git-url)]
    (when scm-url
      {:url scm-url
       :sha (:sha scm-info)})))

(defn ingest-git!
  "Analyze the git repository `repo` and store the result in `storage`"
  [storage {:keys [project version scm-url local-scm pom-revision] :as _repo}]
  {:pre [(string? scm-url)]}
  (let [git-analysis (ana-git/analyze-git-repo project version (or local-scm scm-url) pom-revision)]
    (if (:error git-analysis)
      {:scm-url scm-url :error (:error git-analysis)}
      (do
        (log/info "Importing Articles" (or local-scm scm-url) pom-revision)
        (storage/import-doc
         storage
         (util/version-entity project version)
         {:jar          {}
          :scm          (merge (:scm git-analysis)
                               {:url scm-url
                                :commit (-> git-analysis :scm :rev)})
          :config       (:config git-analysis)
          :doc-tree     (:doc-tree git-analysis)})

        {:scm-url scm-url
         :commit  (-> git-analysis :scm :rev)}))))

(comment

  (def yada "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def bidi "/private/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-bidi-2.1.34476490973326476417/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp bidi)))

  (-> (:pom-str edn)
      (pom/parse)
      (pom/artifact-info))

  (ingest-cljdoc-edn (io/file "data") edn)

  )

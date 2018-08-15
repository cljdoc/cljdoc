(ns cljdoc.server.ingest
  (:require [clojure.java.io :as io]
            [cljdoc.util :as util]
            [cljdoc.analysis.git :as ana-git]
            [cljdoc.util.pom :as pom]
            [cljdoc.util.codox :as codox]
            [clojure.tools.logging :as log]
            [cljdoc.storage.api :as storage]
            [cljdoc.server.routes :as routes]
            [cljdoc.spec]))

(defn ingest-cljdoc-edn
  "Ingest all the API-related information in the passed `cljdoc-edn` data."
  [storage {:keys [codox] :as cljdoc-edn}]
  (let [artifact (pom/artifact-info (pom/parse (:pom-str cljdoc-edn)))]
    (log/info "Verifying cljdoc-edn contents against spec")
    (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)
    (log/info "Importing API into Grimoire")
    (storage/import-api storage artifact (codox/sanitize-macros codox))))

(defn scm-info
  [project pom-str]
  (let [pom-doc  (pom/parse pom-str)
        artifact (pom/artifact-info pom-doc)
        scm-info (pom/scm-info pom-doc)
        project  (str (:group-id artifact) "/" (:artifact-id artifact))
        version  (:version artifact)
        scm-url  (some-> (or (:url scm-info)
                             (if (util/gh-url? (:url artifact))
                               (:url artifact))
                             (util/scm-fallback project))
                         util/normalize-git-url)]
    (when scm-url
      {:url scm-url
       :sha (:sha scm-info)})))

(defn ingest-git!
  [storage {:keys [project version scm-url local-scm pom-revision]}]
  {:pre [(string? scm-url)]}
  (let [git-analysis (ana-git/analyze-git-repo project version (or local-scm scm-url) pom-revision)]
    (if (:error git-analysis)
      {:scm-url scm-url :error (:error git-analysis)}
      (do
        (log/info "Importing Articles into Grimoire" (or local-scm scm-url) pom-revision)
        (storage/import-doc
         storage
         {:group-id (util/group-id project)
          :artifact-id (util/artifact-id project)
          :version version}
         {:jar          {}
          :scm          (merge (:scm git-analysis)
                               {:url scm-url
                                :commit pom-revision})
          :doc-tree     (:doc-tree git-analysis)})

        {:scm-url scm-url
         :commit  (or pom-revision
                      (-> git-analysis :scm :commit)
                      (-> git-analysis :scm :tag :commit))}))))

(comment

  (def yada "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def bidi "/private/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-bidi-2.1.34476490973326476417/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp bidi)))

  (-> (:pom-str edn)
      (pom/parse)
      (pom/artifact-info))

  (ingest-cljdoc-edn (io/file "data") edn)

  )

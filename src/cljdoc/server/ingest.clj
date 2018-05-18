(ns cljdoc.server.ingest
  (:require [clojure.java.io :as io]
            [cljdoc.util :as util]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.util.pom :as pom]
            [clojure.tools.logging :as log]
            [cljdoc.grimoire-helpers]
            [cljdoc.git-repo :as git]
            [cljdoc.spec]))

(defn ingest-cljdoc-edn [data-dir cljdoc-edn]
  (let [pom-doc      (pom/parse (:pom-str cljdoc-edn))
        artifact     (pom/artifact-info pom-doc)
        scm-info     (pom/scm-info pom-doc)
        project      (str (:group-id artifact) "/" (:artifact-id artifact))
        version      (:version artifact)
        git-dir      (io/file data-dir (cljdoc.util/git-dir project version))
        grimoire-dir (doto (io/file data-dir "grimoire") (.mkdir))
        scm-url      (some-> (or (:url scm-info)
                                 (if (util/gh-url? (:url artifact))
                                   (:url artifact))
                                 (util/scm-fallback project))
                             util/ensure-https)]

    (try
      (log/info "Verifying cljdoc-edn contents against spec")
      (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)

      (when-not scm-url
        (throw (ex-info (str "Could not find SCM URL for project " project) {})))
      (log/info "Cloning Git repo" scm-url)
      (git/clone scm-url git-dir)

      (let [repo        (git/->repo git-dir)
            version-tag (git/version-tag repo version)
            pom-scm-sha (:sha scm-info)
            config-edn  (clojure.edn/read-string (git/read-cljdoc-config repo (:name version-tag)))]

        (when-not version-tag
          (do (log/warnf "No version tag found for version %s in %s\n" version scm-url)
              (telegram/no-version-tag project version scm-url)))

        (log/info "Importing Articles into Grimoire")
        (cljdoc.grimoire-helpers/import-doc
         {:version      (cljdoc.grimoire-helpers/version-thing project version)
          :store        (cljdoc.grimoire-helpers/grimoire-store grimoire-dir)
          :git-meta     {:url scm-url
                         :tag (git/version-tag repo version)}
          :doc-tree     (doctree/process-toc
                         (fn slurp-at-rev [f]
                           (git/slurp-file-at
                            repo (or (:name version-tag) "master") f))
                         (:cljdoc.doc/tree config-edn))})

        (log/info "Importing API into Grimoire")
        (cljdoc.grimoire-helpers/import-api
         {:version      (cljdoc.grimoire-helpers/version-thing project version)
          :codox        (:codox cljdoc-edn)
          :grimoire-dir grimoire-dir})

        (telegram/import-completed
         (cljdoc.routes/path-for
          :artifact/version
          {:group-id    (:group-id artifact)
           :artifact-id (:artifact-id artifact)
           :version version}))

        (log/infof "Done with build for %s %s" project version))

      (catch Throwable t
        (throw (ex-info "Exception while running full build" {:project project :version version} t)))
      (finally
        (when (.exists git-dir)
          (cljdoc.util/delete-directory git-dir))))))

(comment

  (def p "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp p)))

    (-> (:pom-str edn)
        (pom/parse)
        (pom/artifact-info))

  (ingest-cljdoc-edn (io/file "data") edn)

  )

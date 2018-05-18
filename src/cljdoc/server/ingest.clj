(ns cljdoc.server.ingest
  (:require [clojure.java.io :as io]
            [cljdoc.util]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.util.telegram :as telegram]
            [clojure.tools.logging :as log]
            [cljdoc.grimoire-helpers]
            [cljdoc.git-repo :as git]
            [cljdoc.spec]))

(defn ingest-cljdoc-edn [data-dir cljdoc-edn]
  (let [project      (-> cljdoc-edn :pom :project cljdoc.util/normalize-project)
        version      (-> cljdoc-edn :pom :version)
        git-dir      (io/file data-dir (cljdoc.util/git-dir project version))
        grimoire-dir (doto (io/file data-dir "grimoire") (.mkdir))
        scm-url      (or (cljdoc.util/scm-url (:pom cljdoc-edn))
                         (cljdoc.util/scm-fallback project))]
    (try
      (log/info "Verifying cljdoc-edn contents against spec")
      (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)

      (when-not scm-url
        (throw (ex-info (str "Could not find SCM URL for project " project " " (:pom cljdoc-edn))
                        {:pom (:pom cljdoc-edn)})))
      (log/info "Cloning Git repo" scm-url)
      (git/clone scm-url git-dir)

      (let [repo        (git/->repo git-dir)
            version-tag (git/version-tag repo version)
            config-edn  (clojure.edn/read-string (git/read-cljdoc-config repo version-tag))]
        (if version-tag
          (do (log/warnf "No version tag found for version %s in %s\n" version scm-url)
              (telegram/no-version-tag project version scm-url)))

        (cljdoc.grimoire-helpers/import-doc
         {:version      (cljdoc.grimoire-helpers/version-thing project version)
          :store        (cljdoc.grimoire-helpers/grimoire-store grimoire-dir)
          :repo-meta    (git/read-repo-meta repo version)
          :doc-tree     (doctree/process-toc
                         (fn slurp-at-rev [f]
                           (git/slurp-file-at
                            repo (if version-tag (.getName version-tag) "master") f))
                         (:cljdoc.doc/tree config-edn))})

        (log/info "Importing API into Grimoire")
        (cljdoc.grimoire-helpers/import-api
         {:version      (cljdoc.grimoire-helpers/version-thing project version)
          :codox        (:codox cljdoc-edn)
          :grimoire-dir grimoire-dir})

        (telegram/import-completed
         (cljdoc.routes/path-for
          :artifact/version
          {:group-id (cljdoc.util/group-id project)
           :artifact-id (cljdoc.util/artifact-id project)
           :version version}))

        (log/infof "Done with build for %s %s" project version))

      (catch Throwable t
        (throw (ex-info "Exception while running full build" {:project project :version version} t)))
      (finally
        (when (.exists git-dir)
          (cljdoc.util/delete-directory git-dir))))))

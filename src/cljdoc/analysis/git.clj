(ns cljdoc.analysis.git
  "Functions to extract useful information from a project's Git repo

  Cljdoc operates on source files as well as a project's Git repository
  to build API documentation and articles. "
  (:require [cljdoc.util :as util]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.git-repo :as git]
            [cljdoc.doc-tree :as doctree]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

(defn analyze-git-repo
  [project version scm-url pom-revision]
  {:post [(map? %)]}
  (let [git-dir (util/system-temp-dir (str "git-" project))]
    (try
      (log/info "Cloning Git repo" scm-url)
      (git/clone scm-url git-dir)

      ;; Stuff that depends on a SCM url being present
      (let [repo        (git/->repo git-dir)
            version-tag (git/version-tag repo version)
            default-branch (.getFullBranch (.getRepository repo))
            revision    (or pom-revision
                            (:name version-tag)
                            (when (.endsWith version "-SNAPSHOT")
                              default-branch))
            git-files   (when revision
                          (git/ls-files repo revision))
            config-edn  (when revision
                          (->> (or (git/read-cljdoc-config repo revision)
                                   ;; in case people add the file later,
                                   ;; also check in default branch
                                   (git/read-cljdoc-config repo default-branch))
                               (edn/read-string)))]

        (when config-edn
          (telegram/has-cljdoc-edn scm-url))
        (if revision
          (do
            (log/info "Analyzing at revision:" revision)
            {:scm      {:files (git/path-sha-pairs git-files)
                        :tag version-tag
                        :rev revision}
             :doc-tree (doctree/process-toc
                        (fn slurp-at-rev [f]
                          ;; We are intentionally relaxed here for now
                          ;; In principle we should only look at files at the tagged
                          ;; revision but if a file has been added after the tagged
                          ;; revision we might as well include it to allow a smooth,
                          ;; even if slightly less correct, UX
                          (or (when revision
                                (git/slurp-file-at repo revision f))
                              (git/slurp-file-at repo "master" f)))
                        (or (:cljdoc.doc/tree config-edn)
                            (get-in @util/hardcoded-config
                                    [(util/normalize-project project) :cljdoc.doc/tree])
                            (doctree/derive-toc git-files)))})

          {:error {:type "no-revision-found"
                   :version-tag version-tag
                   :pom-revision pom-revision}}))
      (catch org.eclipse.jgit.api.errors.InvalidRemoteException e
        {:error {:type "invalid-remote"
                 :msg (.getMessage e)}})
      (catch org.eclipse.jgit.errors.MissingObjectException e
        {:error {:type "unknown-revision"
                 :revision (.getName (.getObjectId e))}})
      (catch org.eclipse.jgit.api.errors.TransportException e
        {:error {:type "clone-failed"
                 :msg (.getMessage e)}})
      (finally
        (when (.exists git-dir)
          (util/delete-directory! git-dir))))))

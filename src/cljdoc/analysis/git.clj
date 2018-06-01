(ns cljdoc.analysis.git
  "Functions to extract useful information from a project's Git repo

  Cljdoc operates on source files as well as a project's Git repository
  to build API documentation and articles. "
  (:require [cljdoc.util :as util]
            [cljdoc.git-repo :as git]
            [cljdoc.doc-tree :as doctree]
            [clojure.edn :as edn]
            [clojure.tools.logging :as log]))

(defn analyze-git-repo [project version scm-url pom-revision]
  (let [git-dir (util/system-temp-dir (str "git-" project))]
    (try
      (log/info "Cloning Git repo" scm-url)
      (git/clone scm-url git-dir)

      ;; Stuff that depends on a SCM url being present
      (let [repo        (git/->repo git-dir)
            version-tag (git/version-tag repo version)
            revision    (or (:name version-tag) pom-revision
                            (when (.endsWith version "-SNAPSHOT")
                              "master"))
            config-edn  (when revision
                          (->> (or (git/read-cljdoc-config repo revision)
                                   ;; in case people add the file later,
                                   ;; also check in master branch
                                   (git/read-cljdoc-config repo "master"))
                               (edn/read-string)))]

        (if revision
          {:scm      {:files (git/ls-files repo revision)
                      :url scm-url
                      :commit pom-revision
                      :tag version-tag}
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
                          (get-in util/hardcoded-config
                                  [(util/normalize-project project) :cljdoc.doc/tree])
                          (doctree/derive-toc git-dir)))}

          (log/warnf "No revision found for version %s in %s\n" version scm-url)))
      (finally
        (when (.exists git-dir)
          (util/delete-directory! git-dir))))))


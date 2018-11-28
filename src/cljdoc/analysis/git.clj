(ns cljdoc.analysis.git
  "Functions to extract useful information from a project's Git repo

  Cljdoc operates on source files as well as a project's Git repository
  to build API documentation and articles. "
  (:require [cljdoc.util :as util]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.git-repo :as git]
            [cljdoc.doc-tree :as doctree]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as spec]
            [clojure.tools.logging :as log]))

(spec/def ::files (spec/map-of string? string?))
(spec/def ::name string?)
(spec/def ::sha string?)
(spec/def ::rev ::sha)
(spec/def ::commit ::sha)

(spec/def ::tag
  (spec/keys :req-un [::name ::sha ::commit]))

(spec/def ::scm
  (spec/keys :req-un [::files ::rev]
             :opt-un [::tag]))

(spec/def ::doc-tree ::doctree/doctree)

(spec/def ::type string?)
(spec/def ::error
  (spec/keys :req-un [::type]))

(spec/fdef analyze-git-repo
  :args (spec/cat :project string?
                  :version string?
                  :scm-url string?
                  :pom-revision (spec/nilable string?))
  :ret (spec/or :ok (spec/keys :req-un [::scm ::doc-tree])
                :err (spec/keys :req-un [::error])))

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
            {:scm      (cond-> {:files (git/path-sha-pairs git-files)
                                :rev revision}
                         version-tag (assoc :tag version-tag))
             :doc-tree (doctree/process-toc
                        (fn [f]
                          ;; We are intentionally relaxed here for now
                          ;; In principle we should only look at files at the tagged
                          ;; revision but if a file has been added after the tagged
                          ;; revision we might as well include it to allow a smooth,
                          ;; even if slightly less correct, UX
                          (or (git/slurp-file-at repo revision f)
                              (and (git/exists? repo "master")
                                   (git/slurp-file-at repo "master" f))))
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

(comment
  (def r (analyze-git-repo "metosin/reitit" "0.2.5" "https://github.metosin/reitit" nil))

  (spec/explain (:ret (spec/get-spec `analyze-git-repo)) r)

  )

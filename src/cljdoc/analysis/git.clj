(ns cljdoc.analysis.git
  "Functions to extract useful information from a project's Git repo

  Cljdoc operates on source files as well as a project's Git repository
  to build API documentation and articles. "
  (:require [cljdoc.util :as util]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.util.scm :as scm]
            [cljdoc.git-repo :as git]
            [cljdoc.doc-tree :as doctree]
            [cljdoc.user-config :as user-config]
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
  (let [git-dir (util/system-temp-dir (str "git-" project))
        ;; While cloning via SSH is generally preferable it requires
        ;; an SSH key and thus more steps while deploying cljdoc.
        ;; By only falling back to SSH when HTTP isn't available
        ;; we avoid this.
        scm-ssh (or (when (git/clonable? (scm/http-uri scm-url))
                      (scm/http-uri scm-url))
                    (scm/ssh-uri scm-url)
                    (scm/fs-uri scm-url))]
    (try
      (log/infof "Cloning Git repo {:url %s :revision %s}" scm-ssh pom-revision)
      (git/clone scm-ssh git-dir)

      ;; Stuff that depends on a SCM url being present
      (let [repo        (git/->repo git-dir)
            version-tag (git/version-tag repo version)
            default-branch (.getBranch (.getRepository repo))
            revision    (or pom-revision
                            (:name version-tag)
                            (when (.endsWith version "-SNAPSHOT")
                              default-branch))
            git-files   (when revision
                          (git/ls-files repo revision))
            config-edn  (when revision
                          (->> (git/read-cljdoc-config repo revision)
                               (edn/read-string)))]

        (when config-edn
          (telegram/has-cljdoc-edn scm-url))
        (if revision
          (do
            (log/info "Analyzing at revision:" revision)
            {:scm      (cond-> {:files (git/path-sha-pairs git-files)
                                :rev revision
                                :branch (.. repo getRepository getBranch)}
                         version-tag (assoc :tag version-tag))
             :config   config-edn
             :doc-tree (doctree/process-toc
                        {:slurp-fn (fn [f]
                                     ;; We are intentionally relaxed here for now
                                     ;; In principle we should only look at files at the tagged
                                     ;; revision but if a file has been added after the tagged
                                     ;; revision we might as well include it to allow a smooth,
                                     ;; even if slightly less correct, UX
                                     (or (git/slurp-file-at repo revision f)
                                         (when (git/exists? repo "master")
                                           (git/slurp-file-at repo "master" f))))
                         :get-contributors (fn [f]
                                             (git/get-contributors repo revision f))}
                        (or (user-config/get-project config-edn project)
                            (get @util/hardcoded-config
                                 (util/normalize-project project))
                            {:cljdoc.doc/tree (doctree/derive-toc git-files)}))})

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

  (spec/explain (:ret (spec/get-spec `analyze-git-repo)) r))

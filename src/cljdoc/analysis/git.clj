(ns cljdoc.analysis.git
  "Functions to extract useful information from a project's Git repo

  Cljdoc operates on source files as well as a project's Git repository
  to build API documentation and articles. "
  (:require [babashka.fs :as fs]
            [cljdoc-shared.proj :as proj]
            [cljdoc.doc-tree :as doc-tree]
            [cljdoc.git-repo :as git-repo]
            [cljdoc.user-config :as user-config]
            [cljdoc.util.scm :as scm]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as spec]
            [clojure.string :as string]
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

(spec/def ::doc-tree ::doc-tree/doctree)

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

(def ^:private hardcoded-config
  ;; some config for projects that do not include their own
  (edn/read-string (slurp (io/resource "hardcoded-projects-config.edn"))))

(defn- cljdoc-config [repo revision]
  (when revision
    (->> (git-repo/read-cljdoc-config repo revision)
         (edn/read-string))))

(defn- git-url [url]
  ;; While cloning via SSH is generally preferable it requires
  ;; an SSH key and thus more steps while deploying cljdoc.
  ;; By only falling back to SSH when HTTP isn't available
  ;; we avoid this.
  (or (when (git-repo/clonable? (scm/http-uri url))
        (scm/http-uri url))
      (scm/ssh-uri url)
      (scm/fs-uri url)))

(defn- validate-docstring-format [config project]
  (when-let [format (user-config/docstring-format config project)]
    (when-not (some #{format} [:plaintext :markdown])
      {:error {:type "invalid-cljdoc-edn"
               :msg "Invalid: :cljdoc/docstring-format"}})))

(defn- validate-languages [config project]
  (when-let [languages (user-config/languages config project)]
    (and languages
         (not (or (= :auto-detect languages)
                  (and (coll? languages)
                       (seq languages)
                       (distinct? languages)
                       (every? #(or (= "clj" %) (= "cljs" %)) languages))))
         {:error {:type "invalid-cljdoc-edn"
                  :msg "Invalid :cljdoc/languages"}})))

(defn- cljdoc-config-error [config project]
  ;; TODO: Replace with proper, full and helpful user cljdoc.edn validation,
  ;; see https://github.com/cljdoc/cljdoc/issues/539
  ;; For now be strict on the new elements we are validating,
  ;; it is much easier to later loosen the reins than tighthen them.
  ;; Also for now: fail fast on first recognized error.
  (or (validate-languages config project)
      (validate-docstring-format config project)))

(defn- scm-config [url repo revision version-tag]
  (when revision
    (cond->
     {:url url
      :files (git-repo/file-sha-map repo revision)
      :rev revision
      :commit revision ;; was this always intended to be a synonym for :rev?
      :branch (git-repo/default-branch repo)}
      version-tag (assoc :tag version-tag))))

(defn- realize-doc-tree [repo project {:keys [rev files]} user-config]
  (let [slurp-fn (fn [f] (git-repo/slurp-file-at repo rev f))]
    (doc-tree/process-toc
     {:slurp-fn slurp-fn
      :get-contributors (fn [f] (git-repo/get-contributors repo rev f))}
     (or (user-config/doc-tree user-config project)
         (get-in hardcoded-config [(proj/normalize project) :cljdoc.doc/tree])
         (doc-tree/derive-toc (keys files) slurp-fn)))))

(defn analyze-git-repo
  [project version scm-url pom-revision]
  {:post [(map? %)]}
  (let [git-dir (-> {:prefix (str "git-" (string/escape  project {\/ \-}))}
                    fs/create-temp-dir
                    fs/file)
        git-url (git-url scm-url)]
    (try
      (log/infof "Cloning Git repo from %s" git-url)
      (git-repo/clone git-url git-dir)
      (with-open [repo (git-repo/->repo git-dir)]
        (let [version-tag (git-repo/version-tag repo version)
              revision (or pom-revision
                           (:name version-tag)
                           (when (string/ends-with? version "-SNAPSHOT")
                             (git-repo/default-branch repo)))]
          (if (not revision)
            {:error {:type "no-revision-found"
                     ;; TODO: Won't these values be nil when revision is nil?
                     :version-tag version-tag
                     :pom-revision pom-revision}}
            (let [user-config (cljdoc-config repo revision)
                  updated-config-tag (git-repo/version-tag repo "cljdoc-" version)
                  updated-config-revision  (:name updated-config-tag)
                  updated-user-config (cljdoc-config repo updated-config-revision)]
              (or (cljdoc-config-error user-config project)
                  (and updated-user-config (cljdoc-config-error updated-user-config project))
                  (let [scm (scm-config scm-url repo revision version-tag)
                        scm-articles  (scm-config scm-url repo updated-config-revision updated-config-tag)
                        merged-config (merge user-config (select-keys updated-user-config [:cljdoc.doc/tree :cljdoc/docstring-format]))
                        scm-doc-tree (or scm-articles scm)]
                    (log/info "Analyzing git repo at revision:" revision)
                    (when updated-config-revision
                      (log/info "... and articles at revision:" updated-config-revision))
                    (cond-> {:scm scm
                             :config merged-config
                             :doc-tree (realize-doc-tree repo project scm-doc-tree merged-config)}
                      scm-articles (assoc :scm-articles scm-articles))))))))
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
        (when (fs/exists? git-dir)
          (fs/delete-tree git-dir))))))

(comment
  (def r (analyze-git-repo "metosin/reitit" "0.2.5" "https://github.com/metosin/reitit" nil))

  (spec/explain (:ret (spec/get-spec `analyze-git-repo)) r))

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

(defn done [project version]
  (log/infof "Done with build for %s %s" project version)
  (telegram/import-completed
   (cljdoc.routes/path-for
    :artifact/version
    {:group-id    (util/group-id project)
     :artifact-id (util/artifact-id project)
     :version version})))

(defn analyze-git-repo [project version scm-url pom-revision]
  (let [git-dir (cljdoc.util/system-temp-dir (str "git-" project))]
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
                               (clojure.edn/read-string)))]

        (if-not revision
          (do (log/warnf "No revision found for version %s in %s\n" version scm-url)
              (telegram/no-version-tag project version scm-url))

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
                        (if revision
                          (git/slurp-file-at repo revision f)
                          (git/slurp-file-at repo "master" f)))
                      (or (:cljdoc.doc/tree config-edn)
                          (get-in cljdoc.util/hardcoded-config
                                  [(cljdoc.util/normalize-project project) :cljdoc.doc/tree])
                          (doctree/derive-toc git-dir)))}))
      (finally
        (when (.exists git-dir)
          (cljdoc.util/delete-directory! git-dir))))))

(defn ingest-cljdoc-edn
  "Ingest all the information in the passed `cljdoc-edn` data.

  This is a large function, doing the following:
  - parse pom.xml from `:pom-str` key
  - assert that the format of `cljdoc-edn` is correct
  - clone the git repo of the project to local disk
  - read the `doc/cljdoc.edn` configuration file from the projects git repo
  - store articles and other version specific data in grimoire
  - store API data in grimoire"
  [data-dir cljdoc-edn]
  {:post [(find % :scm-url)]}
  (let [pom-doc      (pom/parse (:pom-str cljdoc-edn))
        artifact     (pom/artifact-info pom-doc)
        scm-info     (pom/scm-info pom-doc)
        project      (str (:group-id artifact) "/" (:artifact-id artifact))
        version      (:version artifact)
        v-thing      (cljdoc.grimoire-helpers/version-thing project version)
        store        (cljdoc.grimoire-helpers/grimoire-store
                      (doto (io/file data-dir "grimoire") (.mkdir)))
        scm-url      (some-> (or (:url scm-info)
                                 (if (util/gh-url? (:url artifact))
                                   (:url artifact))
                                 (util/scm-fallback project))
                             util/ensure-https)]

    (try
      (log/info "Verifying cljdoc-edn contents against spec")
      (cljdoc.spec/assert :cljdoc/cljdoc-edn cljdoc-edn)
      (cljdoc.grimoire-helpers/write-bare store v-thing)

      (log/info "Importing API into Grimoire")
      (cljdoc.grimoire-helpers/import-api
       {:version      v-thing
        :codox        (:codox cljdoc-edn)
        :store        store})

      (let [git-analysis (and (some? scm-url)
                              (analyze-git-repo project version scm-url (:sha scm-info)))]
        (if git-analysis
          (do
            (log/info "Importing Articles into Grimoire")
            (cljdoc.grimoire-helpers/import-doc
             {:version      v-thing
              :store        store
              :jar          {}
              :scm          (:scm git-analysis)
              :doc-tree     (:doc-tree git-analysis)})

            (done project version)
            {:scm-url scm-url
             :commit  (-> git-analysis :scm :commit)})

          (do
            (done project version)
            {:scm-url scm-url})))

      (catch Throwable t
        (throw (ex-info "Exception while running full build" {:project project :version version} t))))))

(comment

  (def yada "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def bidi "/private/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-bidi-2.1.34476490973326476417/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp bidi)))

  (-> (:pom-str edn)
      (pom/parse)
      (pom/artifact-info))

  (ingest-cljdoc-edn (io/file "data") edn)

  )

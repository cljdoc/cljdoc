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
  (let [pom-doc      (pom/parse (:pom-str cljdoc-edn))
        artifact     (pom/artifact-info pom-doc)
        scm-info     (pom/scm-info pom-doc)
        project      (str (:group-id artifact) "/" (:artifact-id artifact))
        version      (:version artifact)
        v-thing      (cljdoc.grimoire-helpers/version-thing project version)
        git-dir      (io/file data-dir (cljdoc.util/git-dir project version))
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

      (if-not scm-url
        (log/warnf "Could not find SCM URL for project %s v%s" project version)
        (do 
          (log/info "Cloning Git repo" scm-url)
          (git/clone scm-url git-dir)

          ;; Stuff that depends on a SCM url being present
          (let [repo        (git/->repo git-dir)
                version-tag (git/version-tag repo version)
                revision    (or (:name version-tag) (:sha scm-info))
                pom-scm-sha (:sha scm-info)
                config-edn  (->> (or (git/read-cljdoc-config repo revision)
                                     ;; in case people add the file later,
                                     ;; also check in master branch
                                     (git/read-cljdoc-config repo "master"))
                                 (clojure.edn/read-string))]

            (when-not version-tag
              (do (log/warnf "No version tag found for version %s in %s\n" version scm-url)
                  (telegram/no-version-tag project version scm-url)))

            (log/info "Importing Articles into Grimoire")
            (cljdoc.grimoire-helpers/import-doc
             {:version      v-thing
              :store        store
              :jar          {}
              :scm          {:files (git/ls-files repo revision)
                             :url scm-url
                             :commit pom-scm-sha
                             :tag (git/version-tag repo version)}
              :doc-tree     (doctree/process-toc
                             (fn slurp-at-rev [f]
                               (git/slurp-file-at
                                repo (or (:name version-tag) "master") f))
                             (or (:cljdoc.doc/tree config-edn)
                                 (get-in cljdoc.util/hardcoded-config
                                         [(cljdoc.util/normalize-project project) :cljdoc.doc/tree])
                                 (doctree/derive-toc git-dir)))}))))

      (log/infof "Done with build for %s %s" project version)
      (telegram/import-completed
       (cljdoc.routes/path-for
        :artifact/version
        {:group-id    (:group-id artifact)
         :artifact-id (:artifact-id artifact)
         :version version}))

      (catch Throwable t
        (throw (ex-info "Exception while running full build" {:project project :version version} t)))
      (finally
        (when (.exists git-dir)
          (cljdoc.util/delete-directory git-dir))))))

(comment

  (def yada "/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-yada-1.2.103275451937470452410/cljdoc-edn/yada/yada/1.2.10/cljdoc.edn")
  (def bidi "/private/var/folders/tt/hdgn6rc92pv68rscfj8jn8nh0000gn/T/cljdoc-bidi-2.1.34476490973326476417/cljdoc-edn/bidi/bidi/2.1.3/cljdoc.edn")
  (def edn (clojure.edn/read-string (slurp bidi)))

  (-> (:pom-str edn)
      (pom/parse)
      (pom/artifact-info))

  (ingest-cljdoc-edn (io/file "data") edn)

  )

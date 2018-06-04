(ns cljdoc.server.ingest
  (:require [clojure.java.io :as io]
            [cljdoc.util :as util]
            [cljdoc.analysis.git :as ana-git]
            [cljdoc.util.telegram :as telegram]
            [cljdoc.util.pom :as pom]
            [clojure.tools.logging :as log]
            [cljdoc.grimoire-helpers]
            [cljdoc.server.routes :as routes]
            [cljdoc.spec]))

(defn done [project version]
  (log/infof "Done with build for %s %s" project version)
  (telegram/import-completed
   (routes/url-for
    :artifact/version
    :path-params
    {:group-id    (util/group-id project)
     :artifact-id (util/artifact-id project)
     :version version})))

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
                             util/normalize-git-url)]

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
                              (ana-git/analyze-git-repo project version scm-url (:sha scm-info)))]
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
             :commit  (or (-> git-analysis :scm :commit)
                          (-> git-analysis :scm :tag :commit))})

          (do
            (telegram/no-version-tag project version scm-url)
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

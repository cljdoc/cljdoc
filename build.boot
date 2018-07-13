(ns boot.user)

(boot.core/set-env! :dependencies '[[seancorfield/boot-tools-deps "0.4.5" :scope "test"]
                                    [metosin/bat-test "0.4.0" :scope "test"]])
(require '[boot-tools-deps.core :as tools-deps])
(tools-deps/load-deps {})

(require '[boot.pod :as pod]
         '[boot.util :as util]
         '[clojure.java.io :as io]
         '[clojure.spec.alpha :as spec]
         '[cljdoc.renderers.html]
         '[cljdoc.server.system]
         '[cljdoc.server.ingest :as ingest]
         '[integrant.core]
         '[cljdoc.config :as cfg]
         '[cljdoc.analysis.task :as ana]
         '[cljdoc.util]
         '[cljdoc.storage.api :as storage]
         '[cljdoc.util.repositories :as repositories])

(spec/check-asserts true)

(def grimoire-dir
  (io/file "data" "grimoire"))

(deftask grimoire
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   _ jar     JAR     str "Path to jar, may be local, falls back to local ~/.m2 then remote"
   _ pom     POM     str "Path to pom, may be local, falls back to local ~/.m2 then remote"
   s scm-url SCM     str "Git repo to use, may be local"
   r rev     REV     str "Git revision to use, default tries to be smart"]
  (with-pass-thru _
    (let [analysis-result (-> (ana/analyze-impl
                               project
                               version
                               (or jar
                                   (:jar (repositories/local-uris project version))
                                   (:jar (repositories/artifact-uris project version)))
                               (or pom
                                   (:pom (repositories/local-uris project version))
                                   (:pom (repositories/artifact-uris project version))))
                              slurp read-string)
          storage (storage/->GrimoireStorage grimoire-dir)
          scm-info (ingest/scm-info project (:pom-str analysis-result))]
      (util/info "Generating Grimoire store for %s\n" project)
      (ingest/ingest-cljdoc-edn storage analysis-result)
      (ingest/ingest-git! storage
                          {:project project
                           :version version
                           :scm-url (:url scm-info)
                           :local-scm scm-url
                           :pom-revision (or rev (:sha scm-info))}))))

(deftask grimoire-html
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-html-dir (io/file tempd "grimoire-html")]
      (util/info "Generating Grimoire HTML for %s\n" project)
      (.mkdir grimoire-html-dir)
      (require 'cljdoc.renderers.html 'cljdoc.render.rich-text 'cljdoc.spec :reload)
      (cljdoc.renderers.html/write-docs*
       (cljdoc.storage.api/bundle-docs
        (storage/->GrimoireStorage grimoire-dir)
        {:group-id (cljdoc.util/group-id project)
         :artifact-id (cljdoc.util/artifact-id project)
         :version version})
       grimoire-html-dir)
      (-> fs (add-resource tempd) commit!))))

(deftask build-docs
  "This task can be used to build docs for a project locally.

  A jar from the local maven repository will be used
  based on the project and version info.

  `:git` and `:rev` options can be used to supply a local git repository
  that will be used to extract additional data such as doc/cljdoc.edn
  config and Articles."
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   _ jar     JAR     str "Path to jar, may be local, falls back to local ~/.m2 then remote"
   _ pom     POM     str "Path to pom, may be local, falls back to local ~/.m2 then remote"
   _ git     GIT     str "Path to git repo, may be local"
   _ rev     REV     str "Git revision to collect documentation at"]
  (comp (tools-deps/deps)
        (grimoire :project project
                  :version version
                  :jar jar
                  :pom pom
                  :scm-url git
                  :rev rev)
        (grimoire-html :project project :version version)
        (sift :move {#"^public/" "grimoire-html/"})))

(deftask run []
  (comp (with-pass-thru _
          (integrant.core/init
           (cljdoc.server.system/system-config
            (cfg/config))))
        (wait)))

;; Testing ---------------------------------------------------------------------

(ns-unmap 'boot.user 'test)
(require '[metosin.bat-test :as bat])
(deftask test []
  (bat/bat-test :report [:pretty {:type :junit :output-to "target/junit.xml"}]))

(comment
  (def f
    (future
      (boot (watch) (build-docs :project 'bidi :version "2.1.3"))))

  (future-cancel f)

  )

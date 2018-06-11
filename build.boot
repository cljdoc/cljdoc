(ns boot.user)

(def application-deps
  "These are dependencies we can use regardless of what we're analyzing.
  All code using these dependencies does not operate on Clojure source
  files but the Grimoire store and project repo instead."
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/test.check "0.9.0"]
    [com.cognitect/transit-clj "0.8.300"]

    [hiccup "2.0.0-alpha1"]
    [org.asciidoctor/asciidoctorj "1.5.6"]
    [com.atlassian.commonmark/commonmark "0.11.0"]
    [com.atlassian.commonmark/commonmark-ext-gfm-tables "0.11.0"]
    [com.atlassian.commonmark/commonmark-ext-autolink "0.11.0"]
    [com.atlassian.commonmark/commonmark-ext-heading-anchor "0.11.0"]

    [org.slf4j/slf4j-api "1.7.25"]
    [org.eclipse.jgit "4.10.0.201712302008-r"]
    [com.jcraft/jsch.agentproxy.connector-factory "0.0.9"]
    [com.jcraft/jsch.agentproxy.jsch "0.0.9"]

    [org.clojure-grimoire/lib-grimoire "0.10.9"]
    ;; lib-grimpoire depends on an old core-match
    ;; which pulls in other old stuff
    [org.clojure/core.match "0.3.0-alpha5"]

    [integrant "0.7.0-alpha1"]
    [integrant/repl "0.3.0"] ; dev-dependency
    [aero "1.1.2"]
    [org.clojure/tools.logging "0.4.0"]
    [funcool/cuerdas "2.0.5"]
    [spootnik/unilog "0.7.22"]
    [org.jsoup/jsoup "1.11.3"]
    [digest "1.4.8"]
    [tea-time "1.0.0"]

    [io.pedestal/pedestal.service       "0.5.3"]
    [io.pedestal/pedestal.jetty         "0.5.3"]
    [cheshire "5.8.0"]
    [clj-http-lite "0.3.0"] ;TODO replace with clj-http or similar
    [raven-clj "1.6.0-alpha"]
    [io.sentry/sentry-logback "1.7.5"]

    ;; Build-logs DB (sqlite)
    [org.xerial/sqlite-jdbc "3.20.0"]
    [org.clojure/java.jdbc "0.7.0"]
    [ragtime "0.7.2"]

    [expound "0.6.0"]
    [metosin/bat-test "0.4.0" :scope "test"]
    [boot/core "2.7.2"] ; for inclusion in test pod
    [zprint "0.4.9"]])

(boot.core/set-env! :source-paths #{"src" "test"}
                    :resource-paths #{"resources"}
                    :dependencies application-deps)

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
         '[cljdoc.util.repositories :as repositories]
         '[cljdoc.util.boot])

(spec/check-asserts true)

(def grimoire-dir
  (io/file "data" "grimoire"))

(deftask grimoire
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [offline-mapping (first [nil {'bidi "/Users/martin/code/02-oss/bidi/"}])
          tempd           (tmp-dir!)
          analysis-result (-> (cljdoc.util/cljdoc-edn project version)
                              io/resource slurp read-string)
          storage (storage/->GrimoireStorage grimoire-dir)]
      (util/info "Generating Grimoire store for %s\n" project)
      (ingest/ingest-cljdoc-edn storage analysis-result)
      (-> fs (add-resource tempd) commit!))))

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
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (comp (ana/analyze :project project
                     :version version
                     :jarpath (:jar (repositories/artifact-uris project version))
                     :pompath (:pom (repositories/artifact-uris project version)))
        (grimoire :project project :version version)
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

(ns boot.user)

(def application-deps
  "These are dependencies we can use regardless of what we're analyzing.
  All code using these dependencies does not operate on Clojure source
  files but the Grimoire store and project repo instead."
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/test.check "0.9.0"]
    [com.cognitect/transit-clj "0.8.300"]

    [bidi "2.1.3"]
    [hiccup "2.0.0-alpha1"]
    [org.asciidoctor/asciidoctorj "1.5.6"]
    [com.atlassian.commonmark/commonmark "0.11.0"]
    [com.atlassian.commonmark/commonmark-ext-gfm-tables "0.11.0"]
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
    [yada/lean "1.2.11"]
    [org.clojure/tools.logging "0.4.0"]
    [metosin/jsonista "0.1.1"]
    [funcool/cuerdas "2.0.5"]
    [spootnik/unilog "0.7.22"]
    [org.jsoup/jsoup "1.11.3"]
    [digest "1.4.8"]

    ;; Build-logs DB (sqlite)
    [org.xerial/sqlite-jdbc "3.20.0"]
    [org.clojure/java.jdbc "0.7.0"]
    [ragtime "0.7.2"]

    [expound "0.6.0"]])

(boot.core/set-env! :source-paths #{"src"}
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
                              io/resource slurp read-string)]
      (util/info "Generating Grimoire store for %s\n" project)
      (ingest/ingest-cljdoc-edn (io/file "data") analysis-result) 
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire-html
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-html-dir (io/file tempd "grimoire-html")
          grimoire-thing    (-> (grimoire.things/->Group (cljdoc.util/group-id project))
                                (grimoire.things/->Artifact (cljdoc.util/artifact-id project))
                                (grimoire.things/->Version version))
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire HTML for %s\n" project)
      (.mkdir grimoire-html-dir)
      (require 'cljdoc.renderers.html 'cljdoc.render.rich-text 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.renderers.html/->HTMLRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir grimoire-html-dir})
      (-> fs (add-resource tempd) commit!))))

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   ]
  (comp (ana/analyze :project project :version version)
        (grimoire :project project :version version)
        (grimoire-html :project project :version version)
        (sift :move {#"^public/" "grimoire-html/"})))

(deftask run []
  (comp (with-pass-thru _
          (integrant.core/init
           (cljdoc.server.system/system-config
            (cfg/config))))
        (wait)))

(comment
  (def f
    (future
      (boot (watch) (build-docs :project 'bidi :version "2.1.3"))))

  (future-cancel f)

  )

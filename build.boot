(ns boot.user)

(def application-deps
  "These are dependencies we can use regardless of what we're analyzing.
  All code using these dependencies does not operate on Clojure source
  files but the Grimoire store and project repo instead."
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/test.check "0.9.0"]
    [com.cognitect/transit-clj "0.8.300"]

    [confetti "0.2.1"]
    [bidi "2.1.3"]
    [hiccup "2.0.0-alpha1"]
    [org.asciidoctor/asciidoctorj "1.5.6"]
    [com.atlassian.commonmark/commonmark "0.11.0"]
    [com.atlassian.commonmark/commonmark-ext-gfm-tables "0.9.0"]
    [com.atlassian.commonmark/commonmark-ext-heading-anchor "0.11.0"]

    [org.slf4j/slf4j-nop "1.7.25"]
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
    [metosin/jsonista "0.1.1"]])

(boot.core/set-env! :source-paths #{"src"}
                    :resource-paths #{"site" "resources"}
                    :dependencies application-deps)

(require '[boot.pod :as pod]
         '[boot.util :as util]
         '[clojure.java.io :as io]
         '[clojure.spec.alpha :as spec]
         '[cljdoc.git-repo :as gr]
         '[cljdoc.renderers.html]
         '[cljdoc.renderers.transit]
         '[cljdoc.config :as cfg]
         '[cljdoc.grimoire-helpers]
         '[cljdoc.analysis.task :as ana]
         '[cljdoc.util]
         '[cljdoc.util.boot]
         '[confetti.boot-confetti :as confetti])

(spec/check-asserts true)

(when-not (io/resource "config.edn")
  (util/warn "\n  No config.edn file found on classpath.\n")
  (util/warn "  A minimal config.edn file can be found in resources/config.min.edn\n")
  (util/warn "  To use it run the following:\n\n")
  (util/warn "    cp resources/config.min.edn resources/config.edn\n\n")
  (System/exit 1))

(defn set-sync-bucket-opts! []
  (task-options!
   confetti/sync-bucket {:access-key    (cfg/config :default [:aws :access-key])
                         :secret-key    (cfg/config :default [:aws :secret-key])
                         :cloudfront-id (cfg/config :default [:aws :cloudfront-id])
                         :bucket        (cfg/config :default [:aws :s3-bucket-name])}))


(defn docs-path [project version]
  (str "" (cljdoc.util/group-id project) "/" (cljdoc.util/artifact-id project) "/" version "/"))

(def known-gh ;HACK
  {'yada "https://github.com/juxt/yada"})

(deftask import-repo
  "Scans the fileset for a pom.xml for the specified project,
   detects the referenced Github/SCM repo and clones it into
   a git-repo/ subdirectory in the fileset."
  [p project PROJECT     sym "Project to build documentation for"
   v version VERSION     str "Version of project to build documentation for"
   s scm-url  SCM_URL    str  "SCM url to use for cloning the repository"
   _ find-scm SCM_FINDER code "Function which receives the fileset and returns a SCM url"]
  (let [repo-container-dir (atom nil)]
    (with-pre-wrap fs
      (let [tempd   (or @repo-container-dir (tmp-dir!))
            git-dir (io/file tempd (cljdoc.util/git-dir project version))]
        (if-let [scm (or (find-scm fs) (get known-gh project))]
          (do (if @repo-container-dir
                (util/info "Repository for %s already cloned\n" project)
                (util/info "Identified project repository %s\n" scm))
              (.mkdir git-dir)
              (when-not @repo-container-dir
                (gr/clone scm git-dir)
                (reset! repo-container-dir tempd))
              (let [repo (gr/->repo git-dir)]
                (if-let [version-tag (->> (gr/git-tag-names repo)
                                          (filter #(cljdoc.util/version-tag? version %))
                                          first)]
                  (gr/git-checkout-repo repo version-tag)
                  (util/warn "No version tag found for version %s in %s\n" version scm))))
          (throw (ex-info "Could not determine project repository"
                          {:project project :version version})))
        (-> fs (add-resource tempd) commit!)))))

(defn boot-tmpd-containing
  [fs re-ptn]
  {:post [(some? %)]}
  (->> (output-files fs) (by-re [re-ptn]) first :dir))

(defn jar-contents-dir [fileset]
  (some-> (->> (output-files fileset)
               (by-re [#"^jar-contents/"])
               first
               :dir)
          (io/file "jar-contents")))

(defn git-repo-dir [fileset]
  (some-> (boot-tmpd-containing fileset #"^git-repo/")
          (io/file "git-repo")))

#_(deftask codox
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd     (tmp-dir!)
          pom-map   (find-pom-map fs project)
          codox-dir (io/file tempd "codox-docs/")
          cdx-pod (pod/make-pod {:dependencies (into sandbox-analysis-deps
                                                     [[project version]
                                                      '[codox-theme-rdash "0.1.2"]])})]
      (util/info "Generating codox documentation for %s\n" project)
      (let [docs-dir (-> (boot-tmpd-containing fs #"^jar-contents/")
                         (io/file "git-repo" "doc")
                         (.getPath))]
        (boot.util/dbug "Codox doc-paths %s\n" [docs-dir])
        (pod/with-eval-in cdx-pod
          (require 'codox.main)
          (boot.util/dbug "Codox pod env: %s\n" boot.pod/env)
          (->> {:name         ~(name project)
                :version      ~version
                ;; It seems :project is only intended for overrides
                ;; :project      {:name ~(name project), :version ~version, :description ~(:description pom-map)}
                :description  ~(:description pom-map)
                :source-paths [~(.getPath (jar-contents-dir fs))]
                :output-path  ~(.getPath codox-dir)
                ;; Codox' way of determining :source-uri is tricky since it depends working on
                ;; the repository while we are not giving it the repository information but jar-contents
                ;; :source-uri   ~(str (cljdoc.util/scm-url pom-map) "/blob/{version}/{filepath}#L{line}")
                :doc-paths    [~docs-dir]
                :language     nil
                :namespaces   nil
                :metadata     nil
                :writer       nil
                :exclude-vars nil
                :themes       [:rdash]}
               (remove (comp nil? second))
               (into {})
               (codox.main/generate-docs))))
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd          (tmp-dir!)
          grimoire-dir   (io/file tempd "grimoire")
          analysis-result (-> (cljdoc.util/cljdoc-edn project version)
                              io/resource slurp read-string)]
      (util/info "Generating Grimoire store for %s\n" project)
      (cljdoc.grimoire-helpers/import
       {:cljdoc-edn   analysis-result
        :grimoire-dir grimoire-dir
        :git-repo     (-> (cljdoc.util/git-dir project version)
                          io/resource io/file gr/->repo)})
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire-html
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-dir      (-> (boot-tmpd-containing fs #"^grimoire/")
                                (io/file "grimoire/"))
          grimoire-html-dir (io/file tempd "grimoire-html")
          grimoire-thing    (-> (grimoire.things/->Group (cljdoc.util/group-id project))
                                (grimoire.things/->Artifact (cljdoc.util/artifact-id project))
                                (grimoire.things/->Version version))
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire HTML for %s\n" project)
      (.mkdir grimoire-html-dir)
      (require 'cljdoc.renderers.html 'cljdoc.renderers.markup 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.renderers.html/->HTMLRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir grimoire-html-dir})
      (-> fs (add-resource tempd) commit!))))

(deftask transit-cache
  [p project PROJECT sym "Project to build transit cache for"
   v version VERSION str "Version of project to build transit cache for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-dir      (-> (boot-tmpd-containing fs #"^grimoire/")
                                (io/file "grimoire/"))
          grimoire-thing    (-> (grimoire.things/->Group (cljdoc.util/group-id project))
                                (grimoire.things/->Artifact (cljdoc.util/artifact-id project))
                                (grimoire.things/->Version version))
          transit-cache-dir (io/file tempd "transit-cache")
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire Transit cache for %s\n" project)
      (.mkdir transit-cache-dir)
      (require 'cljdoc.renderers.transit 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.renderers.transit/->TransitRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir transit-cache-dir})
      (-> fs (add-resource tempd) commit!))))

(defn open-uri [format-str project version]
  (let [uri (format format-str (cljdoc.util/group-id project) (cljdoc.util/artifact-id project) version)]
    (boot.util/info "Opening %s\n" uri)
    (boot.util/dosh "open" uri)))

(deftask open
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pass-thru _ (open-uri "http://localhost:5000/%s/%s/%s/" project version)))

(deftask deploy-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (set-sync-bucket-opts!)
  (let [doc-path (docs-path project version)]
    (assert (.endsWith doc-path "/"))
    (assert (not (.startsWith doc-path "/")))
    (comp (sift :include #{#"^grimoire-html"})
          (sift :move {#"^grimoire-html/(.*)" (str "$1")})
          ;; TODO find common prefix of all files in the fileset, pass as :invalidation-paths
          ;; TODO remove all uses of `docs-path`
          (confetti/sync-bucket :invalidation-paths [(str "/" doc-path "*")])
          (with-pass-thru _
            (let [base-uri "https://cljdoc.martinklepsch.org"]
              (util/info "\nDocumentation can be viewed at:\n\n    %s/%s\n\n" base-uri doc-path))))))

(defmacro when-task [test task-form]
  `(if ~test ~task-form identity))

(defn read-scm-from-cljdoc-edn [project version]
  (-> (cljdoc.util/cljdoc-edn project version)
      io/resource slurp read-string :pom cljdoc.util/scm-url))

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   d deploy          bool "Also deploy newly built docs to S3?"
   c run-codox       bool "Also generate codox documentation"
   t transit         bool "Also generate transit cache"]
  (comp (ana/copy-jar-contents :jar (cljdoc.util/local-jar-file [project version]))
        (ana/analyze :project project :version version)
        (import-repo :project project
                     :version version
                     :find-scm (fn [_] (read-scm-from-cljdoc-edn project version)))
        (grimoire :project project :version version)
        (grimoire-html :project project :version version)
        (when-task transit
          (transit-cache :project project :version version))
        (when-task deploy
          (deploy-docs :project project :version version))
        #_(when-task run-codox
          (codox :project project :version version))
        #_(open :project project :version version)))

(deftask wipe-s3-bucket []
  (set-sync-bucket-opts!)
  (confetti/sync-bucket :prune true))

(deftask update-site []
  (set-sync-bucket-opts!)
  (set-env! :resource-paths #{"site"})
  (confetti/sync-bucket))

(deftask dev-design
  [p project PROJECT sym "Project to generate docs for"]
  (let [versions {'manifold "0.1.6", 'yada "1.2.11", 'bidi "2.1.3"}]
    (assert (get versions project) (format "project version not specified for project %s" project))
    (boot.util/info "\n\n    To serve files from target/ run ./script/serve.sh (requires `npx`)\n\n")
    (comp (watch)
          (notify :audible true)
          (build-docs :project project
                      :version (get versions project))
          (target))))

(comment
  (def f
    (future
      (boot (watch) (build-docs :project 'bidi :version "2.1.3"))))

  (future-cancel f)

  )

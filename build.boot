(ns boot.user)

(def analysis-deps
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/test.check "0.9.0"]
    [org.clojure/java.classpath "0.2.2"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/clojurescript "1.9.946"] ; Codox depends on old CLJS which fails with CLJ 1.9

    [codox "0.10.3"]
    [org.clojure-grimoire/lib-grimoire "0.10.9"]
    ;; lib-grimpoire depends on an old core-match
    ;; which pulls in other old stuff
    [org.clojure/core.match "0.3.0-alpha5"]
    [me.arrdem/detritus "0.3.0"]])

(boot.core/set-env!
 :source-paths #{"src"}
 :dependencies (into analysis-deps
                     '[[com.cognitect/transit-clj "0.8.300"]

                       [confetti "0.2.0"]
                       [bidi "2.1.3"]
                       [hiccup "2.0.0-alpha1"]

                       [org.slf4j/slf4j-nop "1.7.25"]
                       [clj-jgit "0.8.10"]

                       ;; Samples to test with
                       ;; [org.martinklepsch/derivatives "0.2.0"]
                       ;; [re-frame "0.10.2"]
                       ]))

(require '[boot.pod :as pod]
         '[boot.util :as util]
         '[clojure.java.io :as io]
         '[clojure.spec.alpha :as spec]
         '[clj-jgit.porcelain :as git]
         '[cljdoc.html]
         '[cljdoc.grimoire-helpers]
         '[confetti.boot-confetti :as confetti])

(spec/check-asserts true)

(defn jar-file [coordinate]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(= coordinate (:dep %)))
       (first)
       :jar))

(deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file."]
  (with-pre-wrap fileset
    (let [d (tmp-dir!)]
      (util/info "Unpacking %s\n" jar)
      (pod/unpack-jar jar (io/file d "jar-contents/"))
      (-> fileset (add-resource d) commit!))))

(defn pom-path [project]
  (let [artifact (name project)
        group    (or (namespace project) artifact)]
    (str "META-INF/maven/" group "/" artifact "/pom.xml")))

(defn group-id [project]
  (or (namespace project) (name project)))

(defn artifact-id [project]
  (name project))

(defn docs-path [project version]
  (str "" (group-id project) "/" (artifact-id project) "/" version "/"))

(defn scm-url [pom-map]
  (clojure.string/replace
   (cond (.contains (:url (:scm pom-map)) "github")
         (:url (:scm pom-map))
         (.contains (:url pom-map) "github")
         (:url pom-map))
   #"^http://"
   "https://")) ;; TODO HACK

(defn clone-repo [uri target-dir]
  (util/info "Cloning repo %s\n" uri)
  (git/git-clone uri target-dir))

(defn git-checkout-repo [dir rev]
  (util/info "Checking out revision %s\n" rev)
  (git/git-checkout (git/load-repo dir) rev))

(defn git-tags [dir]
  (->> (git/load-repo dir)
       (.tagList)
       (.call)
       (map #(->> % .getName (re-matches #"refs/tags/(.*)") second))))

(defn version-tag? [pom-version tag]
  (or (= pom-version tag)
      (= (str "v" pom-version) tag)))

(defn find-pom-map [fileset project]
  (when-let [pom (some->> (output-files fileset)
                          (by-path [(str "jar-contents/" (pom-path project))])
                          first
                          tmp-file)]
    ;; TODO assert that only one pom.xml is found
    (pod/with-eval-in pod/worker-pod
      (require 'boot.pom)
      (boot.pom/pom-xml-parse-string ~(slurp pom)))))

(deftask import-repo
  "Scans the fileset for a pom.xml for the specified project,
   detects the referenced Github/SCM repo and clones it into
   a git-repo/ subdirectory in the fileset."
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [pom-map (find-pom-map fs project)
          tempd   (tmp-dir!)
          git-dir (io/file tempd "git-repo")]
      (when-not pom-map
        (util/warn "Could not find pom.xml for %s in fileset\n" project))
      (if-let [scm (scm-url pom-map)]
        (do (util/info "Identified project repository %s\n" scm)
            (.mkdir git-dir)
            (clone-repo scm git-dir)
            (if-let [version-tag (->> (git-tags git-dir)
                                      (filter #(version-tag? version %))
                                      first)]
              (git-checkout-repo git-dir version-tag)
              (util/warn "No version tag found for version %s in %s\n" version scm)))
        (util/warn "Could not determine project repository for %s\n" project))
      (-> fs (add-resource tempd) commit!))))

(defn jar-contents-dir [fileset]
  (let [jar-contents-fileset-dir (->> (output-files fileset)
                                      (by-re [#"^jar-contents/"])
                                      first
                                      :dir)]
    (some-> jar-contents-fileset-dir
            (io/file "jar-contents")
            (.getPath))))

(deftask codox
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd     (tmp-dir!)
          pom-map   (find-pom-map fs project)
          codox-dir (io/file tempd "codox-docs/")
          jar-contents-fileset-dir (->> (output-files fs)
                                        (by-re [#"^jar-contents/"])
                                        first
                                        :dir)
          cdx-pod (pod/make-pod {:dependencies [[project version]
                                                '[codox-theme-rdash "0.1.2"]
                                                '[codox "0.10.3"]]})]
      (util/info "Generating codox documentation for %s\n" project)
      (assert jar-contents-fileset-dir "Could not find jar-contents directory in fileset")
      (let [jar-contents-dir (-> jar-contents-fileset-dir
                                 (io/file "jar-contents")
                                 (.getPath))
            docs-dir (-> jar-contents-fileset-dir
                         (io/file "git-repo" "doc")
                         (.getPath))]
        (boot.util/dbug "Codox source-paths %s\n" [jar-contents-dir])
        (boot.util/dbug "Codox doc-paths %s\n" [docs-dir])
        (pod/with-eval-in cdx-pod
          (require 'codox.main)
          (boot.util/dbug "Codox pod env: %s\n" boot.pod/env)
          (->> {:name         ~(name project)
                :version      ~version
                ;; It seems :project is only intended for overrides
                ;; :project      {:name ~(name project), :version ~version, :description ~(:description pom-map)}
                :description  ~(:description pom-map)
                :source-paths [~jar-contents-dir]
                :output-path  ~(.getPath codox-dir)
                ;; Codox' way of determining :source-uri is tricky since it depends working on
                ;; the repository while we are not giving it the repository information but jar-contents
                ;; :source-uri   ~(str (scm-url pom-map) "/blob/{version}/{filepath}#L{line}")
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
    (let [tempd        (tmp-dir!)
          grimoire-dir (io/file tempd "grimoire")
          grimoire-pod (pod/make-pod {:dependencies (conj analysis-deps [project version])
                                      :directories #{"src"}})]
      (util/info "Generating Grimoire store for %s\n" project)
      (pod/with-eval-in grimoire-pod
        (require 'cljs.util)
        ;; TODO print versions for Clojure/CLJS and other important deps
        (boot.util/info "Pod versions: %s\n" (cljs.util/clojurescript-version))
        (require 'cljdoc.grimoire-helpers)
        (boot.util/info "Grimoire importer version: %s\n" cljdoc.grimoire-helpers/v)
        (doseq [platf ["clj" "cljs"]]
          (cljdoc.grimoire-helpers/build-grim
           {:group-id     ~(group-id project)
            :artifact-id  ~(artifact-id project)
            :version      ~version
            :platform     platf}
           ~(jar-contents-dir fs)
           ~(.getPath grimoire-dir))))
      (-> fs (add-resource tempd) commit!))))

(deftask grimoire-html
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd             (tmp-dir!)
          grimoire-dir      (let [boot-dir (->> (output-files fs) (by-re [#"^grimoire/"]) first :dir)]
                              (assert boot-dir)
                              (io/file boot-dir "grimoire/"))
          grimoire-html-dir (io/file tempd "grimoire-html")
          grimoire-thing    (-> (grimoire.things/->Group (group-id project))
                                (grimoire.things/->Artifact (artifact-id project))
                                (grimoire.things/->Version version))
          grimoire-store   (grimoire.api.fs/->Config (.getPath grimoire-dir) "" "")]
      (util/info "Generating Grimoire HTML for %s\n" project)
      (.mkdir grimoire-html-dir)
      (require 'cljdoc.html 'cljdoc.routes 'cljdoc.spec :reload)
      (cljdoc.cache/render
       (cljdoc.html/->HTMLRenderer)
       (cljdoc.cache/bundle-docs grimoire-store grimoire-thing)
       {:dir grimoire-html-dir})
      (-> fs (add-resource tempd) commit!))))

(defn open-uri [format-str project version]
  (let [uri (format format-str (group-id project) (artifact-id project) version)]
    (boot.util/info "Opening %s\n" uri)
    (boot.util/dosh "open" uri)))

(deftask open
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pass-thru _ (open-uri "http://localhost:5000/%s/%s/%s/" project version)))

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (comp (copy-jar-contents :jar (jar-file [project version]))
        #_(import-repo :project project :version version)
        (grimoire :project project :version version)
        (grimoire-html :project project :version version)
        (open :project project :version version)
        #_(codox :project project :version version)))

(deftask deploy-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (let [doc-path (docs-path project version)]
    (assert (.endsWith doc-path "/"))
    (comp (build-docs :project project :version version)
          (sift :include #{#"^codox-docs"})
          (sift :move {#"^codox-docs/(.*)" (str doc-path "$1")})
          (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"
                                ;; also invalidates root path (also cheaper)
                                :invalidation-paths [(str "/" doc-path "*")])
          (with-pass-thru _
            (let [base-uri "https://cljdoc.martinklepsch.org"]
              (util/info "\nDocumentation can be viewed at:\n\n    %s/%s\n\n" base-uri doc-path))))))

#_(deftask wipe-everything []
  (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"
                        :prune true))

(deftask update-site []
  (set-env! :resource-paths #{"site"})
  (confetti/sync-bucket :confetti-edn "cljdoc-martinklepsch-org.confetti.edn"))

(comment
  (def f
    (future
      (boot (watch) (build-docs :project 'bidi :version "2.1.3"))))

  (future-cancel f)

  )

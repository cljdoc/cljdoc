(set-env! :dependencies '[[org.clojure/clojure "1.8.0"]
                          [org.slf4j/slf4j-nop "1.7.25"]
                          [clj-jgit "0.8.10"]])

(require '[boot.pod :as pod]
         '[boot.util :as util]
         '[clojure.java.io :as io]
         '[clj-jgit.porcelain :as git])

(defn jar-file [coordinate]
  ;; (jar-file '[org.martinklepsch/derivatives "0.2.0"])
  (->> (pod/resolve-dependencies {:dependencies [coordinate]})
       (filter #(= coordinate (:dep %)))
       (first)
       :jar))

(deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file."]
  (boot.core/with-pre-wrap fileset
    (let [d (tmp-dir!)]
      (pod/unpack-jar jar (io/file d "jar-contents/"))
      (-> fileset (boot.core/add-resource d) commit!))))

(defn pom-path [project]
  (let [artifact (name project)
        group    (or (namespace project) artifact)]
    (str "META-INF/maven/" group "/" artifact "/pom.xml")))

(defn scm-url [pom-map]
  (cond (.contains (:url (:scm pom-map)) "github")
        (:url (:scm pom-map))
        (.contains (:url pom-map) "github")
        (:url pom-map)))

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

;; known sparkledriver commit ce2f37e

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
                                      (some #(version-tag? version %)))]
              (git-checkout-repo git-dir version-tag)
              (util/warn "No version tag found for version %s in %s\n" version scm)))
        (util/warn "Could not determine project repository for %s\n" project))
      (-> fs (add-resource tempd) commit!))))

(deftask codox
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (with-pre-wrap fs
    (let [tempd     (tmp-dir!)
          pom-map   (find-pom-map fs project)
          codox-dir (io/file tempd "codox-docs")
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
                :description  ~(:description pom-map)
                :source-paths [~jar-contents-dir]
                :output-path  ~(.getPath codox-dir)
                :source-uri   nil
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

(deftask build-docs
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (comp (copy-jar-contents :jar (jar-file [project version]))
        (import-repo :project project :version version)
        (codox :project project :version version)))

;; Example invocation:
;; boot build-docs --project org.martinklepsch/derivatives --version 0.2.0

;; NEXT STEPS (mostly getting more information from Git repo)
;; - read Github URL from pom.xml or Clojars
;; - clone repo, copy `doc` directory, provide to codox
;; - derive source-uri (probably needs parsing of project.clj or build.boot or perhaps we can derive source locations by overlaying jar contents)
;; - figure out what other metadata should be imported

;; LONG SHOTS
;; - think about how something like dynadoc (interactive docs) could be integrated

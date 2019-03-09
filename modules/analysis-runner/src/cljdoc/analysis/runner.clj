(ns cljdoc.analysis.runner
  "Prepares the environment to run analysis in.

  The `-main` entry point will construct a directory
  with the sources of the analyzed jar present as well
  as some additional files that contain the actual code
  used during analysis. That code is than ran by shelling
  out to `clojure` providing all inputs via `-Sdeps`.

  By shelling out to `clojure` we create an isolated
  environment which does not have the dependencies of
  this namespace (namely jsoup and version-clj)."
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as string]
            [cljdoc.util :as util]
            [cljdoc.analysis.deps :as deps]
            [cljdoc.spec])
  (:import (java.util.zip ZipFile GZIPInputStream)
           (java.net URI)
           (java.nio.file Files)))

(defn unzip!
  [source target-dir]
  (with-open [zip (ZipFile. (io/file source))]
    (let [entries (enumeration-seq (.entries zip))]
      (doseq [entry entries
              :when (not (.isDirectory ^java.util.zip.ZipEntry entry))
              :let [f (io/file target-dir (str entry))]]
        (util/copy (.getInputStream zip entry) f)))))

(defn- download-jar! [jar-uri target-dir]
  (let [jar-f (io/file target-dir "downloaded.jar")]
    (printf "Downloading remote jar...\n")
    (util/copy jar-uri jar-f)
    (.getPath jar-f)))

(defn- clean-jar-contents!
  "Some projects include their `out` directories in their jars,
  usually somewhere under public/, this tries to clear those.

  It also deletes various files that frequently trip up analysis.

  NOTE this means projects with the group-id `public` will fail to build."
  [unpacked-jar-dir]
  (when (.exists (io/file unpacked-jar-dir "public"))
    (println "Deleting public/ dir")
    (util/delete-directory! (io/file unpacked-jar-dir "public")))
  ;; Delete .class files that have a corresponding .clj or .cljc file
  ;; to circle around https://dev.clojure.org/jira/browse/CLJ-130
  ;; This only affects Jars with AOT compiled namespaces where the
  ;; version of Clojure used during compilation is < 1.8.
  ;; This hast mostly been put into place for datascript and might
  ;; get deleted if datascript changes it's packaging strategy.
  (doseq [class-file (->> (file-seq unpacked-jar-dir)
                          (map #(.getAbsolutePath %))
                          (filter (fn clj-or-cljc [path]
                                    (or (.endsWith path ".cljc")
                                        (.endsWith path ".clj"))))
                          (map #(string/replace % #"(\.clj$|\.cljc$)" "__init.class"))
                          (map io/file))]
    (when (.exists class-file)
      (println "Deleting" (.getPath class-file))
      (.delete class-file)))
  (doseq [path ["deps.cljs" "data_readers.clj" "data_readers.cljc"]
          :let [file (io/file unpacked-jar-dir path)]]
    ;; codox returns {:publics ()} for deps.cljs, data_readers.cljc
    ;; when present this should probably be fixed in codox as well
    ;; but just deleting the file will also do the job for now
    (when (.exists file)
      (println "Deleting" path)
      (.delete file))))

(defn- prepare-jar-for-analysis!
  "Prepares a project specified by jar (local or remote) and pom for analysis.

  * jar is unpacked and cleaned
  * dependencies are resolved and downloaded to cache
  * analysis files are copied under target location
  * combined CP of jar+deps+analysis is calculated"
  [jar pom extra-mvn-repos target-dir]
  (let [impl-src-dir (io/file target-dir "impl-src/")
        jar-contents (io/file target-dir "contents/")
        jar-uri    (URI. jar)
        jar-path   (if-let [remote-jar? (boolean (.getHost jar-uri))]
                     (download-jar! jar-uri target-dir)
                     jar)
        {:keys [classpath resolved-deps]} (deps/resolved-and-cp jar-path pom extra-mvn-repos)
        classpath* (str (.getAbsolutePath impl-src-dir) ":" classpath)]
    (unzip! jar-path jar-contents)
    (clean-jar-contents! jar-contents)
    (util/copy (io/resource "impl.clj.tpl")
               (io/file impl-src-dir "cljdoc" "analysis" "impl.clj"))
    (util/copy (io/resource "cljdoc/util.clj")
               (io/file impl-src-dir "cljdoc" "util.clj"))
    {:jar-path jar-path
     :jar-contents jar-contents
     :analysis-files-path impl-src-dir
     :resolved-deps resolved-deps
     :classpath classpath*}))

(defn- print-process-result [proc]
  (printf "exit-code %s ---------------------------------------------------------------\n" (:exit proc))
  (println "stdout --------------------------------------------------------------------")
  (println (:out proc))
  (println "---------------------------------------------------------------------------")
  (when (seq (:err proc))
    (println "stderr --------------------------------------------------------------------")
    (println (:err proc))
    (println "---------------------------------------------------------------------------")))

(defn- analyze-impl
  "Analyze a prepared project."
  [{:keys [project version src-dir pom classpath output-file]}]
  {:pre [(symbol? project) (seq version) (seq pom) (seq classpath)]}
  (let [platforms    (get-in @util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/platforms]
                             (util/infer-platforms-from-src-dir src-dir))
        namespaces   (get-in @util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/namespaces])
        build-cdx      (fn build-cdx [jar-contents-path platf]
                         ;; TODO in theory we don't need to start this clojure process twice
                         ;; and could just modify the code in `impl.clj` to immediately run analysis
                         ;; for all requested platforms
                         (let [f (util/system-temp-file project ".edn")]
                           (println "Analyzing" project platf)
                           (let [process (sh/sh "java"
                                                "-cp" classpath
                                                "clojure.main" "-m" "cljdoc.analysis.impl"
                                                (pr-str namespaces) jar-contents-path platf (.getAbsolutePath f)
                                                ;; supplying :dir is necessary to avoid local deps.edn being included
                                                ;; once -Srepro is finalized it might be useful for this purpose
                                                :dir (.getParentFile f))
                                 _ (print-process-result process)]
                             (if (zero? (:exit process))
                               (let [result (util/read-cljdoc-edn f)]
                                 (assert result "No data was saved in output file")
                                 result)
                               (throw (ex-info (str "Analysis failed with code " (:exit process)) {:code (:exit process)}))))))]

    (let [cdx-namespaces (->> (map #(build-cdx (.getPath src-dir) %) platforms)
                              (zipmap platforms))
          ana-result     {:group-id (util/group-id project)
                          :artifact-id (util/artifact-id project)
                          :version version
                          :codox cdx-namespaces
                          :pom-str (slurp pom)}]
      (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
      (if (every? some? (vals cdx-namespaces))
        (spit output-file (util/serialize-cljdoc-edn ana-result))
        (throw (Exception. "Analysis failed"))))))

(defn analyze!
  "Analyze the provided project"
  [{:keys [project version jarpath pompath repos]}]
  {:pre [(seq project) (seq version) (seq jarpath) (seq pompath)]}
  (let [project (symbol project)
        analysis-dir (util/system-temp-dir (str "cljdoc-" project "-" version))
        output-file  (io/file util/analysis-output-prefix (util/cljdoc-edn project version))]
    (try
      (io/make-parents output-file)

      (let [{:keys [jar-contents classpath resolved-deps]}
            (prepare-jar-for-analysis! jarpath pompath repos analysis-dir)]

        (println "Used dependencies for analysis:")
        (deps/print-tree resolved-deps)
        (println "---------------------------------------------------------------------------")

        (println "Going to write analysis result to:" (.getAbsolutePath output-file))
        (analyze-impl {:project project
                       :version version
                       :src-dir jar-contents
                       :pom pompath
                       :classpath classpath
                       :output-file output-file})

        (println "Analysis succeeded!")
        {:analysis-status :success
         :analysis-result output-file})
      (catch Throwable t
        (let [msg (.getMessage t)]
          (println msg)
          {:analysis-status :fail
           :fail-reason msg}))
      (finally
        (util/delete-directory! analysis-dir)))))

(defn -main
  [project version jarpath pompath]
  (let [{:keys [analysis-status]} (analyze! {:project project
                                             :version version
                                             :jarpath jarpath
                                             :pompath pompath})]
    (shutdown-agents)
    (System/exit (if (= :success analysis-status) 0 1))))

(comment
  (deps 'bidi "2.1.3")

  (analyze-impl 'bidi "2.1.3" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.jar" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.pom")

  (sh/sh "clj" "-Sdeps" (pr-str {:deps deps}) "-m" "cljdoc.analysis.impl" "1" "2" "3")

  {:deps deps})

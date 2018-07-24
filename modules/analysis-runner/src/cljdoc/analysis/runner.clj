(ns cljdoc.analysis.runner
  {:boot/export-tasks true}
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string]
            [cljdoc.util :as util]
            [cljdoc.util.deps :as deps]
            [cljdoc.spec])
  (:import (java.util.zip ZipFile GZIPInputStream)
           (java.net URI)
           (java.nio.file Files)))

(defn copy [uri file]
  (with-open [in  (io/input-stream uri)
              out (io/output-stream file)]
    (io/copy in out)))

(defn unzip!
  [source target-dir]
  (with-open [zip (ZipFile. (io/file source))]
    (let [entries (enumeration-seq (.entries zip))]
      (doseq [entry entries
              :when (not (.isDirectory ^java.util.zip.ZipEntry entry))
              :let [f (io/file target-dir (str entry))]]
        (.mkdirs (.getParentFile f))
        (io/copy (.getInputStream zip entry) f)))))

(defn copy-jar-contents-impl
  [jar-uri target-dir]
  (let [remote-jar? (boolean (.getHost jar-uri))  ; basic check if jar is at remote location
        jar-local (if remote-jar?
                    (let [jar-f (io/file target-dir "downloaded.jar")]
                      (io/make-parents jar-f)
                      (printf "Downloading remote jar...\n")
                      (copy jar-uri jar-f)
                      (.getPath jar-f))
                    (str jar-uri))]
    (printf "Unpacking %s\n" jar-local)
    (unzip! jar-local target-dir)
    ;; Some projects include their `out` directories in their jars,
    ;; usually somewhere under public/, this tries to clear those.
    ;; NOTE this means projects with the group-id `public` will fail to build.
    (when (.exists (io/file target-dir "public"))
      (println "Deleting public/ dir")
      (util/delete-directory! (io/file target-dir "public")))
    (doseq [f ["deps.cljs" "data_readers.clj" "data_readers.cljc"]]
      ;; codox returns {:publics ()} for deps.cljs, data_readers.cljc
      ;; when present this should probably be fixed in codox as well
      ;; but just deleting the file will also do the job for now
      (when (.exists (io/file target-dir f))
        (println "Deleting" f)
        (.delete (io/file target-dir f))))
    (when remote-jar? (.delete (io/file jar-local)))))

(defn print-process-result [proc]
  (printf "exit-code %s ---------------------------------------------------------------\n" (:exit proc))
  (println "stdout --------------------------------------------------------------------")
  (println (:out proc))
  (println "---------------------------------------------------------------------------")
  (when (seq (:err proc))
    (println "stderr --------------------------------------------------------------------")
    (println (:err proc))
    (println "---------------------------------------------------------------------------")))

(defn analyze-impl
  [project version jar pom]
  {:pre [(symbol? project) (seq version) (seq jar) (seq pom)]}
  (let [tmp-dir      (util/system-temp-dir (str "cljdoc-" project "-" version))
        jar-contents (io/file tmp-dir "jar-contents/")
        impl-src-dir (io/file tmp-dir "impl-src/")
        _            (copy (io/resource "impl.clj")
                           (doto (io/file impl-src-dir "cljdoc" "analysis" "impl.clj")
                             (-> .getParentFile .mkdirs)))
        _            (copy-jar-contents-impl (URI. jar) jar-contents)
        platforms    (get-in util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/platforms]
                             (util/infer-platforms-from-src-dir jar-contents))
        namespaces   (get-in util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/namespaces])
        deps           (deps/deps pom project version)
        build-cdx      (fn build-cdx [jar-contents-path platf]
                         (let [f (util/system-temp-file project ".edn")]
                           (println "Analyzing" project platf "- used dependencies:")
                           (println (pr-str {:deps deps}))
                           ;; (println "Classpath:" (:out (sh/sh "clojure" "-Sdeps" (pr-str {:deps deps})
                           ;;                                    "-Spath" :dir (.getParentFile f))))
                           (let [process (sh/sh "clojure" "-Sdeps" (pr-str {:deps deps
                                                                            :paths [(.getAbsolutePath impl-src-dir)]})
                                                "-m" "cljdoc.analysis.impl"
                                                (pr-str namespaces) jar-contents-path platf (.getAbsolutePath f)
                                                ;; supplying :dir is necessary to avoid local deps.edn being included
                                                ;; once -Srepro is finalized it might be useful for this purpose
                                                :dir (.getParentFile f))
                                 result (edn/read-string (slurp f))]
                             (print-process-result process)
                             (when (zero? (:exit process))
                               (assert result "No data was saved in output file")
                               result))))]

    (let [cdx-namespaces (->> (map #(build-cdx (.getPath jar-contents) %) platforms)
                              (zipmap platforms))
          ana-result     {:group-id (util/group-id project)
                          :artifact-id (util/artifact-id project)
                          :version version
                          :codox cdx-namespaces
                          :pom-str (slurp pom)}]
      (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
      (util/delete-directory! jar-contents)
      (if (every? some? (vals cdx-namespaces))
        (doto (io/file tmp-dir (util/cljdoc-edn project version))
          (io/make-parents)
          (spit (pr-str ana-result)))
        (throw (Exception. "Analysis failed"))))))

(defn -main
  "Analyze the provided "
  [project version jarpath pompath]
  (try
    (io/copy (analyze-impl (symbol project) version jarpath pompath)
             (doto (io/file "/tmp/cljdoc/analysis-out" (util/cljdoc-edn project version))
               (-> .getParentFile .mkdirs)))
    (catch Throwable t
      (println (.getMessage t))
      (System/exit 1))
    (finally
      (shutdown-agents))))

(comment
  (deps 'bidi "2.1.3")

  (analyze-impl 'bidi "2.1.3" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.jar" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.pom")

  (sh/sh "clj" "-Sdeps" (pr-str {:deps deps}) "-m" "cljdoc.analysis.impl" "1" "2" "3")

  {:deps deps}



  )

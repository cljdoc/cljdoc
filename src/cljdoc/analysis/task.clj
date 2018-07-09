(ns cljdoc.analysis.task
  {:boot/export-tasks true}
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.java.shell :as sh]
            [clojure.string]
            [cljdoc.util :as util]
            [cljdoc.spec])
  (:import (java.util.zip ZipFile GZIPInputStream)
           (java.net URI)
           (java.nio.file Files)))

(defn extra-deps
  "Some projects require additional depenencies that have either been specified with
  scope 'provided' or are specified via documentation, e.g. a README.
  Maybe should be able to configure this via their cljdoc.edn configuration
  file but this situation being an edge case this is a sufficient fix for now."
  [project]
  (get '{manifold/manifold {org.clojure/core.async {:mvn/version "RELEASE"}}
         io.pedestal/pedestal.interceptor {org.clojure/tools.logging {:mvn/version "RELEASE"}}
         compojure/compojure {javax.servlet/servlet-api {:mvn/version "2.5"}}
         ring/ring-core {javax.servlet/servlet-api {:mvn/version "2.5"}}}
       (symbol (util/normalize-project project))))

(defn deps [project version]
  (merge {project {:mvn/version version}
          'org.clojure/clojure {:mvn/version "1.9.0"}
          'org.clojure/java.classpath {:mvn/version "0.2.2"}
          'org.clojure/tools.namespace {:mvn/version "0.2.11"}
          'org.clojure/clojurescript {:mvn/version "1.10.238"}
          'codox/codox {:mvn/version "0.10.3" :exclusions '[enlive hiccup org.pegdown/pegdown]}}
         (extra-deps project)))

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
    (when (.exists (io/file target-dir "deps.cljs"))
      (println "Deleting deps.cljs")
      (.delete (io/file target-dir "deps.cljs")))
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
        _            (copy-jar-contents-impl (URI. jar) jar-contents)
        platforms    (get-in util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/platforms]
                             (util/infer-platforms-from-src-dir jar-contents))
        namespaces   (get-in util/hardcoded-config
                             [(util/normalize-project project) :cljdoc.api/namespaces])
        build-cdx      (fn build-cdx [jar-contents-path platf]
                         (let [f (util/system-temp-file project ".edn")
                               deps (deps project version)]
                           (println "Analyzing" project platf)
                           (println "Dependencies:" deps)
                           (let [process (sh/sh "clojure" "-Sdeps" (pr-str {:deps deps})
                                                "-m" "cljdoc.analysis.impl"
                                                (pr-str namespaces) jar-contents-path platf (.getAbsolutePath f))
                                 result (edn/read-string (slurp f))]
                             (when-not (zero? (:exit process))
                               (println (:out process))
                               (println (:err process))
                               (throw (Exception. "Process exited with non-zero exit code.")))
                             (print-process-result process)
                             (assert result "No data was saved in output file")
                             result)))]

    (let [cdx-namespaces (->> (map #(build-cdx (.getPath jar-contents) %) platforms)
                              (zipmap platforms))
          ana-result     {:group-id (util/group-id project)
                          :artifact-id (util/artifact-id project)
                          :version version
                          :codox cdx-namespaces
                          :pom-str (slurp pom)}]
      (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
      (util/delete-directory! jar-contents)
      (doto (io/file tmp-dir (util/cljdoc-edn project version))
        (io/make-parents)
        (spit (pr-str ana-result))))))

(defn -main
  "Analyze the provided "
  [project version jarpath pompath]
  (io/copy (analyze-impl (symbol project) version jarpath pompath)
           (doto (io/file "analysis-out" (util/cljdoc-edn project version))
             (-> .getParentFile .mkdirs)))
  (shutdown-agents))

(comment
  (deps 'bidi "2.1.3")

  (analyze-impl 'bidi "2.1.3" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.jar" "/Users/martin/.m2/repository/bidi/bidi/2.1.3/bidi-2.1.3.pom")

  (sh/sh "clj" "-Sdeps" (pr-str {:deps deps}) "-m" "cljdoc.analysis.impl" "1" "2" "3")

  {:deps deps}



  )

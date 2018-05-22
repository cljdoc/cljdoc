(ns cljdoc.analysis.task
  {:boot/export-tasks true}
  (:require [boot.core :as b]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.edn]
            [clojure.string]
            [cljdoc.util]
            [cljdoc.util.boot]
            [cljdoc.spec])
  (:import (java.net URI)
           (java.nio.file Files)))

(defn hardcoded-config []
  (clojure.edn/read-string (slurp (io/resource "hardcoded-projects-config.edn"))))

(def sandbox-analysis-deps
  "This is what is being loaded in the pod that is used for analysis.
  It is also the stuff that we cannot generate documentation for in versions
  other than the ones listed below. (See CONTRIBUTING for details.)"
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/java.classpath "0.2.2"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/clojurescript "1.10.238"] ; Codox depends on old CLJS which fails with CLJ 1.9
    [org.clojure/core.async "RELEASE"] ; Manifold dev-dependency — we should probably detect+load these
    [org.clojure/tools.logging "RELEASE"] ; Pedestal Interceptors dev-dependency — we should probably detect+load these
    [codox "0.10.3" :exclusions [enlive hiccup org.pegdown/pegdown]]])

(defn copy [uri file]
  (with-open [in  (io/input-stream uri)
              out (io/output-stream file)]
    (io/copy in out)))

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
    (pod/unpack-jar jar-local target-dir)
    (when remote-jar? (.delete (io/file jar-local)))))

(b/deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file, may be a URI or a path on local disk."]
  (b/with-pre-wrap fileset
    (let [d (b/tmp-dir!)]
      (copy-jar-contents-impl (URI. jar) (io/file d "jar-contents/"))
      (-> fileset (b/add-resource d) b/commit!))))

(defn system-temp-dir [prefix]
  (.toFile (Files/createTempDirectory
            (clojure.string/replace prefix #"/" "-")
            (into-array java.nio.file.attribute.FileAttribute []))))

(defn analyze-impl
  [project version jar]
  {:pre [(symbol? project) (seq version) (seq jar)]}
  (let [tmp-dir      (system-temp-dir (str "cljdoc-" project "-" version))
        jar-contents (io/file tmp-dir "jar-contents/")
        grimoire-pod (pod/make-pod {:dependencies (conj sandbox-analysis-deps [project version])
                                    :directories #{"src"}})
        platforms    (get-in (hardcoded-config)
                             [(cljdoc.util/normalize-project project) :cljdoc.api/platforms]
                             (cljdoc.util/infer-platforms-from-src-dir jar-contents))
        namespaces   (get-in (hardcoded-config)
                             [(cljdoc.util/normalize-project project) :cljdoc.api/namespaces])
        build-cdx      (fn build-cdx [jar-contents-path platf]
                         (println "Analyzing" project platf)
                         (pod/with-eval-in grimoire-pod
                           (require 'cljdoc.analysis.impl)
                           (cljdoc.analysis.impl/codox-namespaces
                            (quote ~namespaces) ; the unquote seems to be recursive in some sense
                            ~jar-contents-path
                            ~platf)))]

    (copy-jar-contents-impl (URI. jar) jar-contents)

    (let [cdx-namespaces (->> (map #(build-cdx (.getPath jar-contents) %) platforms)
                              (zipmap platforms))
          ana-result     {:codox cdx-namespaces
                          :pom-str (slurp (cljdoc.util/find-pom-in-dir jar-contents project))}]
      (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
      (cljdoc.util/delete-directory jar-contents)
      (doto (io/file tmp-dir (cljdoc.util/cljdoc-edn project version))
        (io/make-parents)
        (spit (pr-str ana-result))))))

(b/deftask analyze
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"
   j jarpath JARPATH str "Absolute path to the jar (optional, local/remote)"]
  (b/with-pre-wrap fs
    (let [tempd           (b/tmp-dir!)
          jar             (or jarpath (cljdoc.util/remote-jar-file [project version]))
          cljdoc-edn-file (analyze-impl project version jar)]
      (io/copy cljdoc-edn-file
               (doto (io/file tempd (cljdoc.util/cljdoc-edn project version))
                 (io/make-parents)))
      (-> fs (b/add-resource tempd) b/commit!))))

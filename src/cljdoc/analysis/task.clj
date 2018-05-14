(ns cljdoc.analysis.task
  {:boot/export-tasks true}
  (:require [boot.core :as b]
            [boot.pod :as pod]
            [boot.util :as util]
            [clojure.java.io :as io]
            [clojure.edn]
            [cljdoc.util]
            [cljdoc.util.boot]
            [cljdoc.spec])
  (:import (java.net URI)))

(defn hardcoded-config []
  (clojure.edn/read-string (slurp (io/resource "hardcoded-projects-config.edn"))))

(def sandbox-analysis-deps
  "This is what is being loaded in the pod that is used for analysis.
  It is also the stuff that we cannot generate documentation for in versions
  other than the ones listed below. (See CONTRIBUTING for details.)"
  '[[org.clojure/clojure "1.9.0"]
    [org.clojure/java.classpath "0.2.2"]
    [org.clojure/tools.namespace "0.2.11"]
    [org.clojure/clojurescript "1.9.946"] ; Codox depends on old CLJS which fails with CLJ 1.9
    [org.clojure/core.async "RELEASE"] ; Manifold dev-dependency — we should probably detect+load these
    [org.clojure/tools.logging "RELEASE"] ; Pedestal Interceptors dev-dependency — we should probably detect+load these
    [codox "0.10.3" :exclusions [enlive hiccup org.pegdown/pegdown]]])

(defn copy [uri file]
  (with-open [in  (io/input-stream uri)
              out (io/output-stream file)]
    (io/copy in out)))

(b/deftask copy-jar-contents
  "Copy the contents of the given jar into the fileset"
  [j jar     PATH  str      "The path of the jar file, may be a URI or a path on local disk."]
  (b/with-pre-wrap fileset
    (let [d         (b/tmp-dir!)
          jar-uri   (URI. jar)
          remote-jar? (boolean (.getHost jar-uri))  ; basic check if jar is at remote location
          jar-local (if remote-jar?
                      (let [jar-f (io/file d "downloaded.jar")]
                        (boot.util/info "Downloading remote jar...\n")
                        (copy jar-uri jar-f)
                        (.getPath jar-f))
                      jar)]
      (boot.util/info "Unpacking %s\n" jar-local)
      (pod/unpack-jar jar-local (io/file d "jar-contents/"))
      (when remote-jar? (.delete (io/file jar-local)))
      (-> fileset (b/add-resource d) b/commit!))))

(defn jar-contents-dir [fileset]
  (some-> (->> (b/output-files fileset)
               (b/by-re [#"^jar-contents/"])
               first
               :dir)
          (io/file "jar-contents")))

(defn digest-dir [dir]
  (->> (file-seq dir)
       (filter #(.isFile %))
       (map #(boot.from.digest/digest "md5" %))
       (apply str)
       (boot.from.digest/digest "md5")))

(b/deftask analyze
  [p project PROJECT sym "Project to build documentation for"
   v version VERSION str "Version of project to build documentation for"]
  (let [jar-contents-md5 (atom nil)
        prev-tmp-dir     (atom nil)]
    (b/with-pre-wrap fs
      (if (= @jar-contents-md5 (digest-dir (jar-contents-dir fs)))
        (-> fs (b/add-resource @prev-tmp-dir) b/commit!)
        (do
          (boot.util/info "Creating analysis pod ...\n")
          (let [tempd        (b/tmp-dir!)
                grimoire-pod (pod/make-pod {:dependencies (conj sandbox-analysis-deps [project version])
                                            :directories #{"src"}})
                platforms    (get-in (hardcoded-config)
                                     [(cljdoc.util/artifact-id project) :cljdoc.api/platforms]
                                     (cljdoc.util/infer-platforms-from-src-dir (jar-contents-dir fs)))
                namespaces   (get-in (hardcoded-config)
                                     [(cljdoc.util/artifact-id project) :cljdoc.api/namespaces])
                build-cdx      (fn [jar-contents-path platf]
                                 (pod/with-eval-in grimoire-pod
                                   (require 'cljdoc.analysis.impl)
                                   (cljdoc.analysis.impl/codox-namespaces
                                    (quote ~namespaces) ; the unquote seems to be recursive in some sense
                                    ~jar-contents-path
                                    ~platf)))
                cdx-namespaces (->> (map #(build-cdx (.getPath (jar-contents-dir fs)) %) platforms)
                                    (zipmap platforms))
                ana-result     {:codox cdx-namespaces
                                ;; TODO do not parse pom here, defer to trusted env for that
                                :pom-str (slurp (cljdoc.util.boot/find-pom fs project))
                                :pom     (-> (cljdoc.util.boot/find-pom fs project)
                                             (cljdoc.util.boot/parse-pom))}]
            (cljdoc.spec/assert :cljdoc/cljdoc-edn ana-result)
            (doto (io/file tempd (cljdoc.util/cljdoc-edn project version))
              (io/make-parents)
              ;; TODO implement spec for this + validate
              (spit (pr-str ana-result)))
            (reset! jar-contents-md5 (digest-dir (jar-contents-dir fs)))
            (reset! prev-tmp-dir tempd)
            (-> fs (b/add-resource tempd) b/commit!)))))))

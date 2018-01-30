(ns cljdoc.util.boot
  (:require [cljdoc.util]
            [boot.core :as boot]
            [boot.pod :as pod]
            [clojure.string]))

;; SCM URL finding -------------------------------------------------------------

(defn pom-path [project]
  (let [artifact (name project)
        group    (or (namespace project) artifact)]
    (str "META-INF/maven/" group "/" artifact "/pom.xml")))

(defn find-pom-map [fileset project]
  (let [pom (some->> (boot/output-files fileset)
                     (boot/by-path [(str "jar-contents/" (pom-path project))])
                     cljdoc.util/assert-first
                     boot/tmp-file)]
    (pod/with-eval-in pod/worker-pod
      (require 'boot.pom)
      (boot.pom/pom-xml-parse-string ~(slurp pom)))))

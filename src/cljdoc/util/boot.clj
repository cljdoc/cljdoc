(ns cljdoc.util.boot
  (:require [cljdoc.util]
            [boot.core :as boot]
            [boot.pod :as pod]
            [clojure.java.io :as io]
            [clojure.string]))

;; SCM URL finding -------------------------------------------------------------

(defn find-pom [fileset project]
  (some->> (boot/output-files fileset)
           (boot/by-path [(str "jar-contents/" (cljdoc.util/pom-path project))])
           cljdoc.util/assert-first
           boot/tmp-file))

(defn parse-pom [^java.io.File pom-file]
  (pod/with-eval-in pod/worker-pod
    (require 'boot.pom)
    (boot.pom/pom-xml-parse-string ~(slurp pom-file))))

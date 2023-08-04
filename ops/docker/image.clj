(ns image
  "Instead of running docker build inside the root directory we have a
  small script that packages all relevant files into a zipfile. This
  zipfile is the basis for the docker image.
  This makes the docker context very predictable and removes the need for
  separate .dockerignore files"
  (:require [babashka.fs :as fs]
            [babashka.tasks :as t]))

(defn create-image [cljdoc-zip cljdoc-version]
  (let [cljdoc-build "cljdoc-build"
        docker-image "cljdoc/cljdoc"
        docker-tag (str docker-image ":" cljdoc-version)]
    (println "building cljdoc docker image from:" cljdoc-zip)
    (println "with version:" cljdoc-version)
    (fs/delete-tree cljdoc-build)
    ;; fs/unzip does not restore executable status, but InfoZip does so shell out.
    (t/shell "unzip" cljdoc-zip "-d" cljdoc-build)
    (fs/copy "Dockerfile" cljdoc-build)
    (t/shell "docker build --no-cache -t" docker-tag cljdoc-build)
    (t/shell "docker tag" docker-tag (str docker-image ":latest"))
    (fs/delete-tree cljdoc-build)))

(defn -main [& args]
  (let [[cljdoc-zip cljdoc-version] args]
    (create-image cljdoc-zip cljdoc-version)))

;; when invoked as script
(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

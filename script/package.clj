(ns package
  (:require [babashka.fs :as fs]
            [babashka.tasks :as t]
            [clojure.string :as str]
            [helper.main :as main]
            [version]))

(defn package []
  ;; TODO: do we need the project-root stuff?
  ;; it was in bash script because package script was called from other dirs
  (let [project-root (-> (t/shell {:out :string} "git rev-parse --show-toplevel") :out str/trim)
        target (fs/file project-root "target")
        zipfile (fs/file target "cljdoc.zip")
        long-sha (-> (t/shell {:out :string} "git rev-parse HEAD") :out str/trim)]
    (println "Packaging" (version/version))
    (println "Zip:" (str zipfile))
    (spit (fs/file project-root "resources-compiled/CLJDOC_VERSION") long-sha)
    (fs/create-dirs target)
    (fs/delete-if-exists zipfile)
    ;; fs/zip does not preserve executable status, but InfoZip does so shell out.
    (t/shell "zip -q -r" zipfile "src" "modules" "script" "resources" "resources-compiled" "deps.edn")))

(defn -main [& _args]
  (package))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

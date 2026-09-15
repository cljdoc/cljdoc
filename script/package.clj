(ns package
  (:require [babashka.fs :as fs]
            [babashka.tasks :as t]
            [clojure.string :as str]
            [compile-java]
            [compile-js]
            [lread.status-line :as status]
            [version]))

(defn task
  {:org.babashka/cli {:spec {:force {:alias :f
                                     :coerce :boolean
                                     :desc "Force package ceation"}}}}
  [{:keys [force]}]
  (status/line :head "Packaging")
  ;; TODO: do we need the project-root stuff?
  ;; it was in bash script because package script was called from other dirs
  (let [project-root (-> (t/shell {:out :string} "git rev-parse --show-toplevel") :out str/trim)
        target (fs/file project-root "target" "build")
        zipfile (fs/file target "cljdoc.zip")]
    ;; this check supports current CI job pipeline which relies on not having to recreate cljdoc.zip
    (if-not (or force (not (fs/exists? zipfile)))
      (status/line :detail "Skipped: %s exists" zipfile)
      (do
        ;; not using task :depends because we don't want these invoked unless necessary
        (status/line :head "Compiling JS, Java")
        (t/run 'compile-js)
        (t/run 'compile-java)
        (let [long-sha (-> (t/shell {:out :string} "git rev-parse HEAD") :out str/trim)]
          (status/line :head "Packaging %s" (version/version))
          (status/line :detail "Zip: %s" (str zipfile))
          (spit (fs/file project-root "resources-compiled/CLJDOC_VERSION") long-sha)
          (fs/create-dirs target)
          (fs/delete-if-exists zipfile)
          ;; fs/zip does not preserve executable status, but InfoZip does so shell out.
          (t/shell "zip -q -r" zipfile
                   "src" "modules" "script"
                   "resources" "resources-compiled"
                   "deps.edn" "target/classes"))))))

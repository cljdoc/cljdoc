(ns compile-java
  (:require [babashka.fs :as fs]
            [babashka.tasks :as tasks]
            [build-shared :as bs]
            [lread.status-line :as status]))

(defn task
  {:org.babashka/cli {:spec {:force {:alias :f
                                     :coerce :boolean
                                     :desc "Force a compile"}}}}
  [{:keys [force]}]
  (status/line :head "Compiling Java sources")
  (if (or force (seq (fs/modified-since bs/class-dir (fs/glob "." "src/**.java"))))
    (tasks/clojure "-T:build" "compile-java")
    (println "Java sources already compiled to" bs/class-dir)))

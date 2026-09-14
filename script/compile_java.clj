(ns compile-java
  (:require [babashka.fs :as fs]
            [babashka.tasks :as task]
            [build-shared :as bs]))

(defn task
  {:org.babashka/cli {:spec {:force {:alias :f
                                     :coerce :boolean
                                     :desc "Force a compile"}}}}
  [{:keys [force]}]
  (if (or force (seq (fs/modified-since bs/class-dir (fs/glob "." "src/**.java"))))
    (task/clojure "-T:build" "compile-java")
    (println "Java sources already compiled to" bs/class-dir)))

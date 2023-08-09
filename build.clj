(ns build
  (:require
   [build-shared :as bs]
   [clojure.tools.build.api :as b]))

(def basis (b/create-basis {:project "deps.edn"}))

(defn compile-java [_opts]
  (println "Compiling java sources")
  (b/javac {:src-dirs bs/sources
            :class-dir bs/class-dir
            :basis basis
            :javac-opts ["--release" "17"
                         "-Xlint:all,-serial,-processing"
                         "-Werror"]} ))

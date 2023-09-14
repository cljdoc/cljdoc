#!/usr/bin/env bb

(ns docker-entrypoint
  (:require [babashka.tasks :as t]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [clojure.string :as str])
  (:import (java.time.format DateTimeFormatter)
           (java.time ZoneOffset)))

(defn preserve-heap-dump [heap-dump-file heap-dump-dir]
  (when (fs/exists? heap-dump-file)
    (let [create-date (-> (fs/creation-time heap-dump-file)
                          .toInstant
                          (.atZone ZoneOffset/UTC))
          new-name (fs/file heap-dump-dir
                            (str "heapdump-"
                                 (.format create-date DateTimeFormatter/ISO_INSTANT)
                                 ".hprof"))]
      (println (format "- found existing %s, preserving to %s.gz" heap-dump-file new-name))
      (fs/move heap-dump-file new-name)
      (t/shell "gzip" new-name))))

(defn turf-old-heap-dumps [heap-dump-dir]
  (let [old-dumps (->> (fs/glob heap-dump-dir "heapdump-*.hprof.gz")
                       (sort-by fs/creation-time)
                       reverse
                       (drop 2))]
    (when (seq old-dumps)
      (println "Turfing old heap dumps:")
      (run! (fn [f]
              (println (str "- " f))
              (fs/delete f))
            old-dumps))))

(defn -main [& _args]
  (println "Preparing heap dump dir")
  (let [data-dir (-> (t/clojure {:out :string}
                                "-M script/get_cljdoc_data_dir.clj")
                     :out
                     str/trim)
        heap-dump-dir (fs/file data-dir "heapdumps")
        heap-dump-file (fs/file heap-dump-dir "heapdump.hprof")]
    (println "- will save heap dump on out of memory error to:" (str heap-dump-file))
    (fs/create-dirs heap-dump-dir)
    (preserve-heap-dump heap-dump-file heap-dump-dir)
    (turf-old-heap-dumps heap-dump-dir)
    (println "Launching cljdoc server")
    #_:clj-kondo/ignore ;; bb.edn requires etaoin wich requires an older version of process that has a more limited signature
    (process/exec "clojure"
                  "-J-Dcljdoc.host=0.0.0.0"
                  "-J-XX:+HeapDumpOnOutOfMemoryError"
                  (format "-J-XX:HeapDumpPath=%s" (str heap-dump-file))
                  "-M" "-m" "cljdoc.server.system")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

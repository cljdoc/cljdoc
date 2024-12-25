#!/usr/bin/env bb

(ns docker-entrypoint
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [babashka.tasks :as t]
            [clojure.string :as str])
  (:import (java.time ZoneOffset)
           (java.time.format DateTimeFormatter)))

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
    (process/exec "clojure"
                  "-J-XshowSettings:vm" ;; prints heap usage to to stderr at startup
                  "-J-Xms1024m"         ;; perhaps temporary, will make it easier to diag mem usage
                  "-J-Xmx1024m"
                  "-J-Dcljdoc.host=0.0.0.0"
                  "-J-XX:+ExitOnOutOfMemoryError"
                  "-J-XX:+HeapDumpOnOutOfMemoryError"
                  "-J-XX:NativeMemoryTracking=summary" ;; perhaps temporary for diagnosis of memory usage
                  ;; perhaps temporary... allow connection via jvisualvm
                  "-J-Dcom.sun.management.jmxremote"
                  "-J-Dcom.sun.management.jmxremote.port=9010"
                  "-J-Dcom.sun.management.jmxremote.rmi.port=9010"
                  "-J-Dcom.sun.management.jmxremote.authenticate=false"
                  "-J-Dcom.sun.management.jmxremote.ssl=false"
                  "-J-Djava.rmi.server.hostname=localhost"
                  (format "-J-XX:HeapDumpPath=%s" (str heap-dump-file))
                  "-M" "-m" "cljdoc.server.system")))

(when (= *file* (System/getProperty "babashka.file"))
  (apply -main *command-line-args*))

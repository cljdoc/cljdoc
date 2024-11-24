(ns cljdoc.server.metrics-logger
  (:require
   [babashka.fs :as fs]
   [cljdoc.server.metrics-native-mem :as native-mem]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [tea-time.core :as tt])
  (:import (javax.management ObjectName)
           (java.lang.management ManagementFactory)))

(set! *warn-on-reflection* true)

(defn- heap-stats []
  (let [mem-usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:heap-used (.getUsed mem-usage)
     :heap-committed (.getCommitted mem-usage)
     :heap-max (.getMax mem-usage)}))

(defn- non-heap-stats []
  (let [mem-usage (.getNonHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    {:non-heap-used (.getUsed mem-usage)
     :non-heap-committed (.getCommitted mem-usage)}))

(defn- cgroup-mem-stats []
  ;; this won't be available on all system but is on our docker container
  (when (fs/exists? "/sys/fs/cgroup/memory.stat")
    (let [stats (->> "/sys/fs/cgroup/memory.stat"
                     slurp
                     str/split-lines
                     (mapv #(str/split % #" "))
                     (into {}))]
      {:cgroup-anon (parse-long (get stats "anon"))
       :cgroup-file (parse-long (get stats "file"))})))

(defn- log-stats []
  (log/info "mem-stats" (merge (heap-stats) (non-heap-stats) (cgroup-mem-stats)))
  (log/info "native-mem-stats" (native-mem/metrics)))

(defmethod ig/init-key :cljdoc/metrics-logger
  [k _opts]
  (do (log/info "Starting" k)
      {::metrics-logger-job (tt/every!
                             60 ;; seconds
                             log-stats)}))

(defmethod ig/halt-key! :cljdoc/metrics-logger
  [k metrics-logger]
  (when metrics-logger
    (log/info "Stopping" k)
    (tt/cancel! (::metrics-logger-job metrics-logger))))

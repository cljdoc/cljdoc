(ns cljdoc.server.metrics-logger
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [tea-time.core :as tt])
  (:import [java.lang.management ManagementFactory]))

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
                     (into {})) ]
      {:cgroup-anon (parse-long (get stats "anon"))
       :cgroup-file (parse-long (get stats "file"))})))

(defn- log-stats []
  (log/info "mem-stats" (merge (heap-stats) (non-heap-stats) (cgroup-mem-stats))))

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

(comment
  (heap-stats)
  ;; => {:heap-used 125461000, :heap-committed 339738624, :heap-max 8401190912}

  (non-heap-stats)
  ;; => {:non-heap-used 117287848, :non-heap-committed 131923968}

  (cgroup-mem-stats)
  ;; => {:cgroup-anon 8859942912, :cgroup-file 5171736576}

  (->> "/sys/fs/cgroup/memory.stat"
       slurp
       str/split-lines
       (mapv #(str/split % #" ")))

  :eoc)

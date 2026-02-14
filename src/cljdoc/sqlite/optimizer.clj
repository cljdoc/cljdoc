(ns cljdoc.sqlite.optimizer
  (:require [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [tea-time.core :as tt])
  (:import (java.util.concurrent TimeUnit)))

(defn- optimize-db! [desc db-spec {:keys [flags]}]
  (let [start (System/currentTimeMillis)
        pragma (cond-> "PRAGMA optimize"
                 flags (str "=" flags))]
    (try
      (jdbc/execute! db-spec [pragma])
      (log/infof "sqlite '%s' completed on %s in %dms" pragma desc (- (System/currentTimeMillis) start))
      (catch Exception e
        (log/errorf e "sqlite '%s' failed on %s" pragma desc)))))

(defn- optimize-dbs! [{:keys [db-spec cache-db-spec]} opts]
  (optimize-db! "cljdoc" db-spec opts)
  (optimize-db! "cache" cache-db-spec opts))

(defmethod ig/init-key :cljdoc/sqlite-optimizer
  [k opts]
  (log/info "Starting" k)
  ;; more aggressive optimize for first run, see https://sqlite.org/pragma.html#pragma_optimize
  (optimize-dbs! opts {:flags "0x10002"})
  (tt/every! (.toSeconds TimeUnit/HOURS 1)
             (.toSeconds TimeUnit/HOURS 1)
             #(optimize-dbs! opts {})))

(defmethod ig/halt-key! :cljdoc/sqlite-optimizer
  [k job]
  (log/info "Stopping" k)
  (tt/cancel! job))

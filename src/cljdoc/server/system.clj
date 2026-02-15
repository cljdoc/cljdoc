(ns cljdoc.server.system
  (:require [babashka.fs :as fs]
            [cljdoc.analysis.service :as analysis-service]
            [cljdoc.config :as cfg]
            [cljdoc.maven-repo :as maven-repo]
            [cljdoc.server.build-log :as build-log]
            [cljdoc.server.clojars-stats]
            [cljdoc.server.db-backup :as db-backup]
            [cljdoc.server.metrics-logger]
            [cljdoc.server.pedestal]
            [cljdoc.server.release-monitor]
            [cljdoc.sqlite.optimizer]
            [cljdoc.storage.api :as storage]
            [cljdoc.util.sqlite-cache :as sqlite-cache]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [ragtime.core :as ragtime]
            [ragtime.next-jdbc :as ragtime-next-jdbc]
            [taoensso.nippy :as nippy]
            [tea-time.core :as tt]))

(defn system-config
  "Return integrant system config, injecting `env-config` appropriately.
  See cljdoc.config and resources/config.edn for config loading and values."
  [env-config]
  (let [ana-service (cfg/get-in env-config [:cljdoc/server :analysis-service])
        enable-db-restore? (cfg/get-in env-config [:cljdoc/server :enable-db-restore?])
        enable-db-backup? (cfg/get-in env-config [:cljdoc/server :enable-db-backup?])]
    (merge
     {:cljdoc/tea-time           {}
      :cljdoc/metrics-logger     (ig/ref :cljdoc/tea-time)
      :cljdoc/db-spec            {:data-dir (cfg/get-in env-config [:cljdoc/server :dir])}
      :cljdoc/db                 (merge {:db-spec (ig/ref :cljdoc/db-spec)}
                                        (cond-> {:enable-db-restore? enable-db-restore?}
                                          enable-db-restore? (merge (cfg/get-in env-config [:secrets :s3 :backups]))))
      :cljdoc/cache-db-spec      {:data-dir (cfg/get-in env-config [:cljdoc/server :dir])}
      :cljdoc/cache              {:table "cache"
                                  :key-col "key"
                                  :value-col "value"
                                  :db-spec (ig/ref :cljdoc/cache-db-spec)
                                  :serialize-fn   nippy/freeze
                                  :deserialize-fn nippy/thaw}
      :cljdoc/cached-pom-fetcher {:cache (ig/ref :cljdoc/cache)
                                  :key-prefix "get-pom-xml"
                                  :maven-repositories (cfg/get-in env-config [:maven-repositories])}
      :cljdoc/sqlite-optimizer   {:db-spec (ig/ref :cljdoc/db-spec)
                                  :cache-db-spec (ig/ref :cljdoc/cache-db-spec)
                                  :tea-time (ig/ref :cljdoc/tea-time)}
      :cljdoc/search-index-dir   {:data-dir (cfg/get-in env-config [:cljdoc/server :dir])}
      :cljdoc/searcher           {:index-dir       (ig/ref :cljdoc/search-index-dir)
                                  :enable-indexer? (cfg/get-in env-config [:cljdoc/server :enable-artifact-indexer?])
                                  :tea-time        (ig/ref :cljdoc/tea-time)
                                  :clojars-stats   (ig/ref :cljdoc/clojars-stats)}
      :cljdoc/pedestal-connector {:port                (cfg/get-in env-config [:cljdoc/server :port])
                                  :host                (get-in env-config [:cljdoc/server :host])
                                  :opensearch-base-url (cfg/get-in env-config [:cljdoc/server :opensearch-base-url])
                                  :build-tracker       (ig/ref :cljdoc/build-tracker)
                                  :analysis-service    (ig/ref :cljdoc/analysis-service)
                                  :storage             (ig/ref :cljdoc/storage)
                                  :cached-pom-fetcher  (ig/ref :cljdoc/cached-pom-fetcher)
                                  :searcher            (ig/ref :cljdoc/searcher)
                                  :cljdoc-version      (cfg/get-in env-config [:cljdoc/version])
                                  :maven-repositories  (cfg/get-in env-config [:maven-repositories])}
      :cljdoc/pedestal           (ig/ref :cljdoc/pedestal-connector)
      :cljdoc/storage            {:db-spec (ig/ref :cljdoc/db-spec)}
      :cljdoc/db-backup          (merge {:db-spec (ig/ref :cljdoc/db-spec)
                                         :cache-db-spec (ig/ref :cljdoc/cache-db-spec)}
                                        (cond-> {:enable-db-backup? enable-db-backup?}
                                          enable-db-backup? (merge (cfg/get-in env-config [:secrets :s3 :backups]))))
      :cljdoc/build-tracker      {:db-spec (ig/ref :cljdoc/db-spec)}
      :cljdoc/analysis-service   {:service-type ana-service
                                  :opts (merge
                                         {:repos (->> (cfg/get-in env-config [:maven-repositories])
                                                      (map (fn [{:keys [id url]}] [id {:url url}]))
                                                      (into {}))}
                                         (when (= ana-service :circle-ci)
                                           {:api-token       (cfg/get-in env-config [:secrets :circle-ci :api-token])
                                            :builder-project (cfg/get-in env-config [:secrets :circle-ci :builder-project])}))}
      :cljdoc/clojars-stats      {:db-spec (ig/ref :cljdoc/db-spec)
                                  :retention-days (cfg/get-in env-config [:cljdoc/server :clojars-stats-retention-days])
                                  :tea-time (ig/ref :cljdoc/tea-time)}}

     (when (cfg/get-in env-config [:cljdoc/server :enable-release-monitor?])
       {:cljdoc/release-monitor {:db-spec  (ig/ref :cljdoc/db-spec)
                                 :maven-repositories (cfg/get-in env-config [:maven-repositories])
                                 :build-tracker (ig/ref :cljdoc/build-tracker)
                                 :server-port (cfg/get-in env-config [:cljdoc/server :port])
                                 :max-retries 10
                                 :dry-run? (not (cfg/get-in env-config [:cljdoc/server :autobuild-clojars-releases?]))
                                 :searcher (ig/ref :cljdoc/searcher)
                                 :tea-time (ig/ref :cljdoc/tea-time)}}))))

(defmethod ig/init-key :cljdoc/search-index-dir [_ {:keys [data-dir]}]
  (let [lucene-version (-> org.apache.lucene.util.Version/LATEST
                           str
                           (string/replace "." "_"))
        dir-name (str "index-lucene-" lucene-version)]
    (str (fs/file data-dir dir-name))))

(defmethod ig/init-key :cljdoc/db-spec [_ {:keys [data-dir]}]
  {:dbtype "sqlite",
   :host :none
   :foreign_keys true
   :cache_size 10000
   :dbname (str (fs/file data-dir "cljdoc.db.sqlite"))
   ;; These settings are permanent but it seems like
   ;; this is the easiest way to set them. In a migration
   ;; they fail because they return results.
   :synchronous "NORMAL"
   :journal_mode "WAL"})

(defmethod ig/init-key :cljdoc/analysis-service [k {:keys [service-type opts]}]
  (log/info "Starting" k)
  (case service-type
    :circle-ci (analysis-service/circle-ci opts)
    :local     (analysis-service/map->Local opts)))

(defmethod ig/init-key :cljdoc/storage [k {:keys [db-spec]}]
  (log/info "Starting" k)
  (storage/->SQLiteStorage db-spec))

(defmethod ig/init-key :cljdoc/tea-time [k _]
  (log/info "Starting" k)
  (tt/start!))

(defmethod ig/halt-key! :cljdoc/tea-time [k _]
  (log/info "Stopping" k)
  (tt/stop!))

(defmethod ig/init-key :cljdoc/build-tracker [k {:keys [db-spec]}]
  (log/info "Starting" k)
  (build-log/->SQLBuildTracker db-spec))

(defmethod ig/init-key :cljdoc/db [_ {:keys [enable-db-restore? db-spec] :as opts}]
  (fs/create-dirs (fs/parent (:dbname db-spec)))
  (when enable-db-restore?
    (db-backup/restore-db! opts))
  (ragtime/migrate-all (ragtime-next-jdbc/sql-database db-spec)
                       {}
                       (ragtime-next-jdbc/load-resources "migrations")
                       {:reporter (fn [_store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))})
  db-spec)

(defmethod ig/init-key :cljdoc/cache-db-spec [_ {:keys [data-dir]}]
  {:dbtype "sqlite"
   :host :none
   :dbname (str (fs/file data-dir "cache.db"))})

(defmethod ig/init-key :cljdoc/cache [_ {:keys [db-spec] :as cache-opts}]
  (fs/create-dirs (fs/parent (:dbname db-spec)))
  cache-opts)

(defmethod ig/init-key :cljdoc/cached-pom-fetcher [_ {:keys [cache key-prefix maven-repositories]}]
  (sqlite-cache/memo-sqlite
   (maven-repo/pom-fetcher maven-repositories)
   (assoc cache :key-prefix key-prefix)))

(defn -main []
  (try
    (ig/init
     (cljdoc.server.system/system-config
      (cfg/config)))
    (deref (promise))
    (catch Throwable e
      (log/fatal e "Unexpected exception")
      (System/exit 1))))

(comment
  ;; This is the main REPL entry point into cljdoc.
  ;; Run the forms one by one up to `go` and you should
  ;; have a running server at http://localhost:8000
  ;; OR run then all at once:
  (do
    (require '[cljdoc.server.system])
    (in-ns 'cljdoc.server.system)
    (require '[integrant.repl])
    (integrant.repl/set-prep! #(system-config (cfg/config)))
    (integrant.repl/go))

  (do
    (require '[integrant.repl])
    (integrant.repl/set-prep! #(system-config (cfg/config)))
    (integrant.repl/go))

  (require '[clojure.spec.test.alpha :as st])
  (require '[integrant.repl])

  (integrant.repl/set-prep! #(system-config (cfg/config)))
  (integrant.repl/go)
  (integrant.repl/halt)
  (integrant.repl/clear)
  (integrant.repl/reset)

  integrant.repl.state/system
  integrant.repl.state/config
  integrant.repl.state/preparer

  (do (integrant.repl/halt)
      (st/instrument)
      (integrant.repl/go))

  (do (integrant.repl/halt)
      (integrant.repl/go))

  nil)

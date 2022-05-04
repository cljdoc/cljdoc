(ns cljdoc.storage.storage
  (:require [integrant.core :as ig]
            [cljdoc.storage.postgres-impl :as postgres]
            [cljdoc.storage.sqlite-impl :as sqlite]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]
            [cljdoc.config :as cfg]
            [ragtime.clj.core]))                            ;; allow Clojure-based migrations

(defn config [env-config]
  {:sqlite-spec   {:db-spec (cfg/db env-config)             ;TODO refactoring: invert key nesting: db-spec -> postgres / sqlite
                   :dir     (cfg/data-dir env-config)}
   :postgres-spec {:db-spec (cfg/postgres-db env-config)}
   :active-db     (get-in env-config [:cljdoc/server :active-db])})

(defn run-migrations! [db-spec]
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources (:migrations-dir db-spec))
                       {:reporter (fn [_store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))}))

(defmethod ig/init-key :cljdoc/storage [k {:keys [postgres-spec sqlite-spec active-db]}]
  (log/info "Starting" k)
  (log/info "Using storage mode" active-db)
  (.mkdirs (io/file (:dir sqlite-spec)))
  (doseq [db-spec [postgres-spec sqlite-spec]]
    (run-migrations! (:db-spec db-spec)))                 ;TODO get rid of nested db-spec key
  (case active-db
    :sqlite (sqlite/->SQLiteStorage (:db-spec sqlite-spec)) ;TODO get rid of nested db-spec key
    :postgres (postgres/->PostgresStorage (:db-spec postgres-spec))))

(comment
  (def sqlite-spec {:db-spec (cfg/db (cfg/config))
                    :dir     (cfg/data-dir (cfg/config))})
  (def postgres-spec {:db-spec (cfg/postgres-db (cfg/config))})
  (run-migrations! postgres-spec)
  (run-migrations! sqlite-spec))

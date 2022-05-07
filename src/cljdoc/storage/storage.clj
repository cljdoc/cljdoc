(ns cljdoc.storage.storage
  (:require [integrant.core :as ig]
            [cljdoc.storage.postgres-impl :as ps]
            [cljdoc.storage.sqlite-impl :as sq]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]
            [ragtime.jdbc :as jdbc]
            [ragtime.core :as ragtime]
            [cljdoc.config :as cfg]
            [ragtime.clj.core]))                            ;; allow Clojure-based migrations

(defn config [env-config]
  {:db-spec {:postgres (cfg/postgres-db env-config)
             :sqlite (cfg/db env-config)}
   :data-dir (cfg/data-dir env-config)
   :active-db     (get-in env-config [:cljdoc/server :active-db])})

(defn run-migrations! [db-spec]
  (ragtime/migrate-all (jdbc/sql-database db-spec)
                       {}
                       (jdbc/load-resources (:migrations-dir db-spec))
                       {:reporter (fn [_store direction migration]
                                    (log/infof "Migrating %s %s" direction migration))}))

(defmethod ig/init-key :cljdoc/storage [k {{:keys [postgres sqlite]} :db-spec
                                           :keys [active-db data-dir]}]
  (log/info "Starting" k)
  (log/info "Using storage mode" active-db)
  (.mkdirs (io/file data-dir))
  (doseq [db-spec [postgres sqlite]]
    (run-migrations! db-spec))
  (case active-db
    :sqlite (sq/->SQLiteStorage sqlite)
    :postgres (ps/->PostgresStorage postgres)))

(comment
  (def sqlite-spec (cfg/db (cfg/config)))
  (def postgres-spec (cfg/postgres-db (cfg/config)))
  (run-migrations! postgres-spec)
  (run-migrations! sqlite-spec))

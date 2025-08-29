(ns migrations.010-convert-builds-error-info-from-exception-to-data
  "Adds new error_info_map column to builds table leaving existing error_info column to support
   reversion to old code if necessary."
  (:require [clojure.tools.logging :as log]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as sql]
            [taoensso.nippy :as nippy]))

(defn up [db-spec]
  (binding [])
  (let [cnt (-> (sql/query db-spec ["select count(*) as c from builds where error_info is not null"]
                           {:builder-fn rs/as-unqualified-maps})
                first
                :c)]
    (log/info "rows to udpate:" cnt)
    (jdbc/execute-one! db-spec ["alter table builds add error_info_map"])
    (doseq [row (sql/query db-spec ["select id, error_info from builds where error_info is not null"]
                           {:builder-fn rs/as-unqualified-maps})
            :let [id (:id row)
                  ;; For security reasons, newer versions of nippy are restrictive on what
                  ;; classes they will thaw. For this conversion from exception-object to
                  ;; generic data, we temporarily revert to old nippy behavior of thawing any old object.
                  ex (binding [nippy/*thaw-serializable-allowlist* #{"*"}]
                       (nippy/thaw (:error_info row)))
                  ex-as-data (nippy/freeze (Throwable->map ex))]]
      (sql/update! db-spec :builds {:error_info_map ex-as-data} {:id id}))))

(defn down [db-spec]
  ;; sqlite3 supports dropping columns starting with v3.35.5, but we are not using
  ;; a current version of sqlite3 in production as of this writing.
  ;; Also relying on a current version might be a slightly annoying to setup for macOS devs as
  ;; macOS includes and uses an slightly out of date version of sqlite.
  )

(comment
  (def db-spec {:dbtype "sqlite" :dbname "data/cljdoc.db.sqlite"})
  (def before (sql/query db-spec ["select id, error_info from builds where error_info is not null"]
                         {:builder-fn rs/as-unqualified-maps}))
  (count before);; => 589

  (up db-spec)

  (def after (sql/query db-spec ["select id, error_info, error_info_map from builds where error_info_map is not null"]
                        {:builder-fn rs/as-unqualified-maps}))
  (count after);; => 589

  nil)

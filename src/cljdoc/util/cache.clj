(ns cljdoc.util.cache
  "Provides `SQLCache`, an implementation of `clojure.core.cache/CacheProtocol`.

  It is used with `clojure.core.memoize/PluggableMemoization`

  This namespace exposes `memo-sqlite` function which takes a
  function to memoize and cache-spec, it retuns the memoized function.
  This function uses the `SQLCache` for memoization.
  "
  (:require [clojure.core.cache :as cache]
            [clojure.core.memoize :as memo]
            [taoensso.nippy :as nippy]
            [clojure.java.jdbc :as sql]))

(def query-templates
  {:select "SELECT * FROM %s WHERE prefix = ? AND %s = ?"
   :delete "DELETE FROM %s WHERE prefix = ? AND %s = ?"
   :insert "INSERT OR IGNORE INTO %s (prefix, %s, %S) VALUES (?, ?, ?)"})

(cache/defcache SQLCache [state]
  cache/CacheProtocol
  (lookup [_ k]
          (delay
           (let [{:keys [db key-prefix de-serialize-fn table key-col value-col]}
                 (:cache-spec state)
                 query (format (:select query-templates) table key-col)
                 row-fn #(some-> % (get (keyword value-col)) de-serialize-fn)]
             (sql/query db
                        [query key-prefix (pr-str k)]
                        {:row-fn row-fn :result-set-fn first}))))
  (lookup [this k not-found]
          (delay (or (deref (cache/lookup this k))) not-found))
  (has? [_ k]
        (let [{:keys [db key-prefix de-serialize-fn table key-col value-col]}
              (:cache-spec state)
              query (format (:select query-templates) table key-col)
              row-fn #(some-> % (get (keyword value-col)) de-serialize-fn)]
          (not
           (empty?
            (sql/query db
                       [query key-prefix (pr-str k)]
                       {:row-fn row-fn :result-set-fn first})))))
  (hit [_ k]
       (SQLCache. state))
  (miss [_ k v]
        (let [{:keys [db key-prefix serialize-fn table key-col value-col]}
              (:cache-spec state)
              value (serialize-fn @v)
              query (format (:insert query-templates) table key-col value-col)]
          (sql/execute! db [query key-prefix (pr-str k) value])
          (SQLCache. state)))
  (evict [_ k]
         (let [{:keys [db key-prefix table key-col]} (:cache-spec state)
               query (format (:delete query-templates) table key-col)]
           (sql/execute! db [query key-prefix (pr-str k)])
           (SQLCache. state)))
  (seed [_ base]
        (let [{:keys [db table key-col value-col]} (:cache-spec base)
              column-specs {:prefix             "text"
                            (keyword key-col)   "text"
                            (keyword value-col) "text"}
              query (sql/create-table-ddl table
                                          column-specs
                                          {:conditional? true})]
          (sql/execute! db [query])
          (SQLCache. base)))

  Object
  (toString [_] (str state)))

(defn memo-sqlite
  "Memoizes the given function `f` using  `SQLCache` for caching.
  `SQLCache` uses a SQL backend to store return values of `f`.

  Example usage with SQLite:
  ```
  (def memo-f
    (memo-sqlite (fn [arg] (Thread/sleep 5000) arg)
                 {:key-prefix         \"artifact-repository\"
                  :key-col            \"key\"
                  :value-col          \"value\"
                  :table              \"cache\"
                  :serialize-fn       identity
                  :de-serialize-fn    read-string
                  :db   {:dbtype      \"sqlite\"
                         :classname   \"org.sqlite.JDBC\"
                         :subprotocol \"sqlite\"
                         :subname     \"data/my-cache.db\"}}))

  (memo-f 1) ;; takes more than 5 seconds to return.
  (memo-f 1) ;; return immediately from cache.
  ```
  "
  [f cache-spec]
  (memo/build-memoizer
   #(memo/->PluggableMemoization
     % (cache/seed (SQLCache. {}) {:cache-spec cache-spec}))
   f))

(comment

  (def db-artifact-repository
    {:key-prefix         "artifact-repository"
     :table              "cache2"
     :key-col            "key"
     :value-col          "val"
     :serialize-fn       taoensso.nippy/freeze
     :de-serialize-fn    taoensso.nippy/thaw
     :db   {:dbtype      "sqlite"
            :classname   "org.sqlite.JDBC"
            :subprotocol "sqlite"
            :subname     "data/cache.db"}})

  (time (cljdoc.util.repositories/find-artifact-repository 'bidi "2.1.3"))

  (def memoized-find-artifact-repository
    (memo-sqlite cljdoc.util.repositories/find-artifact-repository
                 db-artifact-repository))

  (time (memoized-find-artifact-repository 'bidi "2.1.3"))

  (time (memoized-find-artifact-repository 'com.bhauman/spell-spec "0.1.0"))

  (memo/memo-clear!
   memoized-find-artifact-repository '(com.bhauman/spell-spec "0.1.0"))

  (time (memoized-find-artifact-repository 'com.bhauman/spell-spec "0.1.0"))

  (time (cljdoc.util.repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT"))

  (def db-artifact-uris
    {:key-prefix         "artifact-uris"
     :table              "cache2"
     :key-col            "key"
     :value-col          "val"
     :serialize-fn       identity
     :de-serialize-fn    read-string
     :db   {:dbtype      "sqlite"
            :classname   "org.sqlite.JDBC"
            :subprotocol "sqlite"
            :subname     "data/cache.db"}})

  (def memoized-artifact-uris
    (memo-sqlite cljdoc.util.repositories/artifact-uris
                  db-artifact-uris))

  (time (memoized-artifact-uris 'bidi "2.0.9-SNAPSHOT"))
  (time (memoized-artifact-uris 'com.bhauman/spell-spec "0.1.0"))

  )

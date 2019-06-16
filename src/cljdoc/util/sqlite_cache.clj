(ns cljdoc.util.sqlite-cache
  "Provides `SQLCache`, an implementation of `clojure.core.cache/CacheProtocol`.

  It is used with `clojure.core.memoize/PluggableMemoization`

  This namespace exposes `memo-sqlite` function which takes a
  function to memoize and cache-spec, it retuns the memoized function.
  This function uses the `SQLCache` for memoization.
  "
  (:require [clojure.core.cache :as cache]
            [clojure.core.memoize :as memo]
            [taoensso.nippy :as nippy]
            [clojure.java.jdbc :as sql])
  (:import (java.time Instant)))

(def query-templates
  {:fetch   "SELECT * FROM %s WHERE prefix = ? AND %s = ?"
   :evict   "DELETE FROM %s WHERE prefix = ? AND %s = ?"
   :cache   "INSERT OR IGNORE INTO %s (prefix, cached_ts, ttl ,%s, %S) VALUES (?, ?, ?, ?, ?)"
   :refresh "UPDATE %s SET cached_ts = ?, ttl = ?, %s = ? WHERE prefix = ? and %s = ?"})

(defn stale?
  "Tests if `ttl` has expired for a cached item.

  When `ttl` is `nil` item is not stale.
  Otherwise `ttl` is compared with `cached_ts`."
  [{:keys [ttl cached_ts]}]
  (if (nil? ttl)
    false
    (> (- (System/currentTimeMillis)
          (.toEpochMilli (Instant/parse cached_ts)))
       ttl)))

(defn fetch-item!
  "Performs lookup by querying on the cache table.
  Retuens deserialized cached item."
  [k {:keys [db-spec key-prefix deserialize-fn table key-col value-col]}]
  (let [query (format (:fetch query-templates) table key-col)
        row-fn #(some-> % (get (keyword value-col)) deserialize-fn)]
    (sql/query db-spec
               [query key-prefix (pr-str k)]
               {:row-fn row-fn :result-set-fn first})))

(defn fetch!
  [k {:keys [db-spec key-prefix table key-col]}]
  (let [query (format (:fetch query-templates) table key-col)]
    (sql/query db-spec [query key-prefix (pr-str k)] {:result-set-fn first})))

(defn refresh!
  [k v {:keys [db-spec key-prefix table key-col value-col serialize-fn ttl]}]
  (let [query (format (:refresh query-templates) table value-col key-col)
        value (serialize-fn @v)]
    (sql/execute! db-spec [query (Instant/now) ttl value key-prefix (pr-str k)])))

(defn cache!
  [k v {:keys [db-spec key-prefix serialize-fn table key-col value-col ttl]}]
  (let [query (format (:cache query-templates) table key-col value-col)
        value (serialize-fn @v)]
    (sql/execute! db-spec [query key-prefix (Instant/now) ttl (pr-str k) value])))

(defn evict!
  [k {:keys [db-spec key-prefix table key-col]}]
  (let [query (format (:evict query-templates) table key-col)]
    (sql/execute! db-spec [query key-prefix (pr-str k)])))

(defn seed!
  [{:keys [db-spec key-prefix table key-col value-col]}]
  (let [column-specs [[:ttl "INTEGER"]
                      [:prefix "TEXT" "NOT NULL"]
                      [:cached_ts "TEXT" "NOT NULL"]
                      [(keyword key-col) "TEXT" "NOT NULL"]
                      [(keyword value-col) "TEXT"]
                      [(format "CONSTRAINT unique_prefix_and_key UNIQUE (prefix, %s)" key-col)]]
        query (sql/create-table-ddl table
                                    column-specs
                                    {:conditional? true})]
    (sql/execute! db-spec [query])))

(cache/defcache SQLCache [state]
  cache/CacheProtocol
  (lookup [_ k]
    (delay (fetch-item! k (:cache-spec state))))
  (lookup [this k not-found]
    (delay (or (deref (cache/lookup this k))) not-found))
  (has? [_ k]
    (let [item (fetch! k (:cache-spec state))]
      (and (not (nil? item))
           (not (stale? item)))))
  (hit [_ k]
    (SQLCache. state))
  (miss [_ k v]
    (let [item (fetch! k (:cache-spec state))]
      (if (and (not (nil? item)) (stale? item))
        (refresh! k v (:cache-spec state))
        (cache! k v (:cache-spec state))))
    (SQLCache. state))
  (evict [_ k]
    (evict! k (:cache-spec state))
    (SQLCache. state))
  (seed [_ base]
    (seed! (:cache-spec base))
    (SQLCache. base))
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
                  :ttl                2000
                  :table              \"cache\"
                  :serialize-fn       identity
                  :deserialize-fn     read-string
                  :db-spec   {:dbtype      \"sqlite\"
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
     :ttl                2000
     :serialize-fn       taoensso.nippy/freeze
     :deserialize-fn     taoensso.nippy/thaw
     :db-spec            {:dbtype      "sqlite"
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

  (memo/memo-clear! memoized-find-artifact-repository)

  (time (memoized-find-artifact-repository 'com.bhauman/spell-spec "0.1.0"))

  (time (cljdoc.util.repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT"))

  (def db-artifact-uris
    {:key-prefix         "artifact-uris"
     :table              "cache2"
     :key-col            "key"
     :value-col          "val"
     :serialize-fn       identity
     :deserialize-fn     read-string
     :db-spec            {:dbtype      "sqlite"
                          :classname   "org.sqlite.JDBC"
                          :subprotocol "sqlite"
                          :subname     "data/cache.db"}})

  (def memoized-artifact-uris
    (memo-sqlite cljdoc.util.repositories/artifact-uris
                  db-artifact-uris))

  (time (memoized-artifact-uris 'bidi "2.0.9-SNAPSHOT"))
  (time (memoized-artifact-uris 'com.bhauman/spell-spec "0.1.0")))



(ns cljdoc.util.cache
  "Provides `SQLCache`, an implementation of `clojure.core.cache/CacheProtocol`.
  `SQLCache` makes following assumptions:

  1. A table named CACHE exists in the database.
  2. A column named KEY exists in CACHE.
  3. A column named VALUE exists in CACHE.

  It is used with `clojure.core.memoize/PluggableMemoization`
  to provide a `clojure.core/memoize`  like API.

  This namespace exposes `memo-sqlite` function which takes a
  function to memoize and cache-spec, it retuns the memoized function.
  "
  (:require [clojure.core.cache :as cache]
            [clojure.core.memoize :as memo]
            [clojure.java.jdbc :as sql]
            [taoensso.nippy :as nippy]))

(defn value->
  [result]
  (some-> result
          first
          :value
          nippy/thaw))

(cache/defcache SQLCache [state]
  cache/CacheProtocol
  (lookup [_ k]
          (delay
           (let [{:keys [spec key-prefix]} (:db state)
                 query "SELECT * FROM CACHE WHERE PREFIX = ? AND KEY = ?"]
             (value-> (sql/query spec [query key-prefix (pr-str k)])))))
  (lookup [_ k not-found]
          (delay
           (let [{:keys [spec key-prefix]} (:db state)
                 query "SELECT * FROM CACHE WHERE PREFIX = ? AND KEY = ?"]
             (or
              (value-> (sql/query spec [query key-prefix (pr-str k)]))
              not-found))))
  (has? [_ k]
        (let [{:keys [spec key-prefix]} (:db state)
              query "SELECT * FROM CACHE WHERE PREFIX = ? AND KEY = ?"]
          (boolean
           (value-> (sql/query spec [query key-prefix (pr-str k)])))))
  (hit [_ k]
       (SQLCache. state))
  (miss [_ k v]
        (let [{:keys [spec key-prefix]} (:db state)
              value (nippy/freeze @v)
              query "INSERT OR IGNORE INTO CACHE (PREFIX, KEY, VALUE) VALUES (?, ?, ?)"]
          (sql/execute! spec [query key-prefix (pr-str k) value])
          (SQLCache. state)))
  (evict [_ k]
         (let [{:keys [spec key-prefix]} (:db state)
               query "DELETE FROM CACHE WHERE KEY = ? AND PREFIX = ?"]
           (sql/execute! spec [query key-prefix (pr-str k)])
           (SQLCache. state)))
  (seed [_ base]
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
                  :spec {:dbtype      \"sqlite\"
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
     % (cache/seed (SQLCache. {}) {:db cache-spec}))
   f))

(comment

  (def db-artifact-repository
    {:key-prefix         "artifact-repository"
     :spec {:dbtype      "sqlite"
            :classname   "org.sqlite.JDBC"
            :subprotocol "sqlite"
            :subname     "data/cache.db"}})

  (time (cljdoc.util.repositories/find-artifact-repository 'bidi "2.1.3"))

  (def memoized-find-artifact-repository
    (memo-sqlite cljdoc.util.repositories/find-artifact-repository
                  db-artifact-repository))

  (time (memoized-find-artifact-repository 'bidi "2.1.3"))
  (time (memoized-find-artifact-repository 'com.bhauman/spell-spec "0.1.0"))

  (time (cljdoc.util.repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT"))

  (def db-artifact-uris
    {:key-prefix         "artifact-uris"
     :spec {:dbtype      "sqlite"
            :classname   "org.sqlite.JDBC"
            :subprotocol "sqlite"
            :subname     "data/cache.db"}})

  (def memoized-artifact-uris
    (memo-sqlite cljdoc.util.repositories/artifact-uris
                  db-artifact-uris))

  (time (memoized-artifact-uris 'bidi "2.0.9-SNAPSHOT"))
  (time (memoized-artifact-uris 'com.bhauman/spell-spec "0.1.0"))

  )

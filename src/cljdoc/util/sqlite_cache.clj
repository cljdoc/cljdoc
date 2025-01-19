(ns cljdoc.util.sqlite-cache
  "Provides `SQLCache`, an implementation of `clojure.core.cache/CacheProtocol`.

  It is used with `clojure.core.memoize/PluggableMemoization`

  This namespace exposes `memo-sqlite` function which takes a
  function to memoize and cache-spec, it retuns the memoized function.
  This function uses `SQLCache` for memoization."
  (:require [clojure.core.cache :as cache]
            [clojure.core.memoize :as memo]
            [clojure.string :as string]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (java.time Clock Instant)))

(defn- derefable? [v]
  (instance? clojure.lang.IDeref v))

(def query-templates
  {:fetch   "SELECT * FROM %s WHERE prefix = ? AND %s = ?"
   :evict   "DELETE FROM %s WHERE prefix = ? AND %s = ?"
   :cache   "INSERT OR IGNORE INTO %s (prefix, cached_ts, ttl ,%s, %S) VALUES (?, ?, ?, ?, ?)"
   :refresh "UPDATE %s SET cached_ts = ?, ttl = ?, %s = ? WHERE prefix = ? and %s = ?"
   :clear   "DELETE FROM %s WHERE prefix = ?"})

(defn stale?
  "Tests if `ttl` has expired for a cached item.

  When `ttl` is `nil` item is not stale.
  Otherwise `ttl` is compared with `cached_ts`."
  [{:keys [ttl cached_ts]} {:keys [clock]}]
  (if (nil? ttl)
    false
    (> (- (.toEpochMilli (Instant/now @clock))
          (.toEpochMilli (Instant/parse cached_ts)))
       ttl)))

(defn fetch-item!
  "Performs lookup by querying on the cache table.
  Returns deserialized cached item."
  [k {:keys [db-spec key-prefix deserialize-fn table key-col value-col]}]
  (let [query (format (:fetch query-templates) table key-col)]
    (some-> (jdbc/execute-one! db-spec
                               [query key-prefix (pr-str k)]
                               {:builder-fn rs/as-unqualified-maps})
            (get (keyword value-col))
            deserialize-fn)))

(defn fetch!
  [k {:keys [db-spec key-prefix table key-col]}]
  (let [query (format (:fetch query-templates) table key-col)]
    (jdbc/execute-one! db-spec [query key-prefix (pr-str k)]
                       {:builder-fn rs/as-unqualified-maps})))

(defn refresh!
  [k v {:keys [clock db-spec key-prefix table key-col value-col serialize-fn ttl]}]
  (let [query (format (:refresh query-templates) table value-col key-col)
        value (serialize-fn @v)]
    (jdbc/execute-one! db-spec [query (Instant/now @clock) ttl value key-prefix (pr-str k)])))

(defn cache!
  [k v {:keys [clock db-spec key-prefix serialize-fn table key-col value-col ttl]}]
  (let [query (format (:cache query-templates) table key-col value-col)
        value (serialize-fn @v)]
    (jdbc/execute-one! db-spec [query key-prefix (Instant/now @clock) ttl (pr-str k) value])))

(defn evict!
  [k {:keys [db-spec key-prefix table key-col]}]
  (let [query (format (:evict query-templates) table key-col)]
    (jdbc/execute-one! db-spec [query key-prefix (pr-str k)])))

(defn seed!
  [{:keys [db-spec table key-col value-col]}]
  ;; this might seem a bit odd, ya'd think this table would be created by db migrations,
  ;; but it is useful for REPL testing
  (let [create-cmd (string/join " " [(format "create table if not exists %s (" table)
                                     "ttl INTEGER,"
                                     "prefix TEXT NOT NULL,"
                                     "cached_ts TEXT NOT NULL,"
                                     (format "%s TEXT NOT NULL," key-col)
                                     (format "%s TEXT NOT NULL," value-col)
                                     (format "CONSTRAINT unique_prefix_and_key UNIQUE (prefix, %s)" key-col)
                                     ")"])]
    (jdbc/execute! db-spec [create-cmd])))

(defn clear-all!
  [{:keys [db-spec key-prefix table]}]
  (let [query (format (:clear query-templates) table)]
    (jdbc/execute! db-spec [query key-prefix])))

(defn- d-ref [v]
  (if (derefable? v) (deref v) v))

;; memoize kind of assumes we are carrying around our cache in state.
;; this is not the case for us, our state is our config and never changes
;; after init.
(cache/defcache SQLCache [state]
  cache/CacheProtocol
  (lookup [_ k]
    (delay (fetch-item! k (:cache-spec state))))
  (lookup [_this k _not-found]
    (when-let [item (fetch-item! k (:cache-spec state))]
      (delay item)))
  (has? [_ k]
    (let [item (fetch! k (:cache-spec state))]
      (and (not (nil? item))
           (not (stale? item (:cache-spec state))))))
  (hit [this _k]
    this)
  (miss [this k v]
    ;; never cache nil values
    (when (not (nil? (d-ref v)))
      (let [item (fetch! k (:cache-spec state))]
        (if (and (not (nil? item)) (stale? item (:cache-spec state)))
          (refresh! k v (:cache-spec state))
          (cache! k v (:cache-spec state)))))
    this)
  (evict [this k]
    (evict! k (:cache-spec state))
    this)
  (seed [this base]
    (if (empty? base)
      ;; if we are being seeded with an empty base, it is a request from memoize to clear the cache
      (do
        (clear-all! (:cache-spec state))
        this)
      ;; otherwise we are being initialized
      (do
        (seed! (:cache-spec base))
        (SQLCache. base))))
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
                  :db-spec   {:dbtype \"sqlite\"
                              :host   :none
                              :dbname \"data/my-cache.db\"}}))

  (memo-f 1) ;; takes more than 5 seconds to return.
  (memo-f 1) ;; return immediately from cache.
  ```

  `cache-spec` can optionally override `:clock` for testing.

  "
  [f {:keys [clock] :as cache-spec}]
  (let [cache-spec (assoc cache-spec :clock (or clock (atom (Clock/systemUTC)) ))]
    (memo/build-memoizer
      #(memo/->PluggableMemoization
         % (cache/seed (SQLCache. {}) {:cache-spec cache-spec}))
      f)))

(comment
  (require '[taoensso.nippy :as nippy])

  (def db-artifact-repository
    {:key-prefix         "artifact-repository"
     :table              "cache2"
     :key-col            "key"
     :value-col          "val"
     :ttl                10000 ;; milliseconds
     :serialize-fn       nippy/freeze
     :deserialize-fn     nippy/thaw
     :db-spec            {:dbtype   "sqlite"
                          :host     :none
                          :dbname   "data/cache.db"}})

  (require '[cljdoc.util.repositories :as repo])

  (defn silly-slow-finder [project version]
    (Thread/sleep 1500)
    (repo/find-artifact-repository project version))

  (time (silly-slow-finder 'bidi "2.1.3"))
  ;; "Elapsed time: 1658.114228 msecs"

  (def memoized-finder
    (memo-sqlite silly-slow-finder
                 db-artifact-repository))

  (time (memoized-finder 'bidi "2.1.3"))

  (time (memoized-finder 'neverfindme "2.1.3"))

  (time (memoized-finder 'com.bhauman/spell-spec "0.1.0"))

  (memo/memo-clear! memoized-finder '(com.bhauman/spell-spec "0.1.0"))

  (memo/memo-clear! memoized-finder)

  (time (memoized-finder 'com.bhauman/spell-spec "0.1.0"))

  (time (cljdoc.util.repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT"))

  (def db-artifact-uris
    {:key-prefix         "artifact-uris"
     :table              "cache2"
     :key-col            "key"
     :value-col          "val"
     :serialize-fn       identity
     :deserialize-fn     read-string
     :db-spec            {:dbtype      "sqlite"
                          :host        :none
                          :dbname     "data/cache.db"}})

  (def memoized-artifact-uris
    (memo-sqlite cljdoc.util.repositories/artifact-uris
                 db-artifact-uris))

  (time (memoized-artifact-uris 'bidi "2.0.9-SNAPSHOT"))
  (time (memoized-artifact-uris 'com.bhauman/spell-spec "0.1.0"))

  nil)

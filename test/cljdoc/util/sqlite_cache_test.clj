(ns cljdoc.util.sqlite-cache-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.core.memoize :as memo]
            [cljdoc.util.sqlite-cache :as c])
  (:import (java.time Instant)) )

(def database-filename "data/unit-test-cache.db")

(def cache-config
  {:key-prefix         "unit-test-prefix"
   :table              "unittestcache"
   :key-col            "key"
   :value-col          "val"
   :ttl                2000
   :serialize-fn       identity
   :deserialize-fn     read-string
   :db-spec            {:dbtype      "sqlite"
                        :classname   "org.sqlite.JDBC"
                        :subprotocol "sqlite"
                        :subname database-filename}})

(defn get-memoed-for-test
  "returns memoized fn and an underlying atom that will, if changed, change fn return value"
  [cache-config]
  (let [v (atom "original")
        raw-fn (fn [a b] (str a "-" b "!" @v))]
    [(c/memo-sqlite raw-fn cache-config) v]))

(defn wrap-delete-db-before [f]
  (when (.exists (io/file database-filename))
    (io/delete-file database-filename))
  (f))

(t/use-fixtures :once wrap-delete-db-before)

(t/deftest caches
  (let [[memoed-fn v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn[] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'cache-me!original (memoed-fn "cache" "me")))
      (reset! v "new")
      (t/is (= 'cache-me!original (memoed-fn "cache" "me"))))))

(t/deftest refreshes_after_ttl
  (let [[memoed-fn v] (get-memoed-for-test cache-config)
        fake-time (atom "2019-07-25T10:30:45.00Z")]
    (with-redefs [c/instant-now (fn[] (Instant/parse @fake-time))]
      (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
      (reset! v "new")
      (reset! fake-time "2019-07-25T10:30:47.00Z")
      (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
      (reset! fake-time "2019-07-25T10:30:47.01Z")
      (t/is (= 'refresh-me!new (memoed-fn "refresh" "me"))))))

(t/deftest can-explicitly-clear-an-item
  (let [[memoed-fn v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn[] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'explicit-clear!original (memoed-fn "explicit" "clear")))
      (reset! v "new")
      (memo/memo-clear! memoed-fn '("explicit" "clear"))
      (t/is (= 'explicit-clear!new (memoed-fn "explicit" "clear"))))))

(t/deftest config-without-ttl-does-not-auto-refresh
  (let [[memoed-fn v] (get-memoed-for-test (dissoc cache-config :ttl))
        fake-time (atom "2019-07-25T10:30:45.00Z")]
    (with-redefs [c/instant-now (fn[] (Instant/parse @fake-time))]
      (t/is (= 'never-refresh!original (memoed-fn "never" "refresh")))
      (reset! v "new")
      (reset! fake-time "2030-07-25T10:30:47.01Z")
      (t/is (= 'never-refresh!original (memoed-fn "never" "refresh"))))))

(t/deftest can-explicitly-clear-all-items
  (let [[memoed-fn v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn[] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'clear-all!original (memoed-fn "clear" "all")))
      (t/is (= 'the-things!original (memoed-fn "the" "things")))
      (reset! v "new")
      (memo/memo-clear! memoed-fn)
      (t/is (= 'clear-all!new (memoed-fn "clear" "all")))
      (t/is (= 'the-things!new (memoed-fn "the" "things"))))))

(t/deftest preserves-cache-on-new-memoize
  (let [[memoed-fn _v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn[] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'preserve-me!original (memoed-fn "preserve" "me")))))
  (let [[memoed-fn v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn[] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (reset! v "new")
      (t/is (= 'preserve-me!original (memoed-fn "preserve" "me"))))) )

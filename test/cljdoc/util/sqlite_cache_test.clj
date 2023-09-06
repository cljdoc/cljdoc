(ns cljdoc.util.sqlite-cache-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.core.memoize :as memo]
            [cljdoc.util.sqlite-cache :as c])
  (:import [clojure.lang ExceptionInfo]
           [java.time Instant]))

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
                        :dbname database-filename}})

(defn get-memoed-for-test
  "returns memoized fn and
  - atom `v` that will, if changed, change fn return value
  - atom `c` that counts calls to to underlying raw function"
  [cache-config]
  (let [v (atom "original")
        c (atom 0)
        raw-fn (fn [a b]
                 (swap! c inc)
                 (cond
                   (= "throw" a)
                   (throw (ex-info "some unfixable problem, sorry" {}))

                   (= "return-nil" a)
                   nil

                   :else
                   (str a "-" b "!" @v)))]
    [(c/memo-sqlite raw-fn cache-config) v c]))

(defn wrap-delete-db-before [f]
  (when (.exists (io/file database-filename))
    (io/delete-file database-filename))
  (f))

(t/use-fixtures :once wrap-delete-db-before)

(t/deftest caches
  (let [[memoed-fn v c] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'cache-me!original (memoed-fn "cache" "me")))
      (t/is (= 1 @c))
      (reset! v "new")
      (t/is (= 'cache-me!original (memoed-fn "cache" "me")))
      (t/is (= 1 @c)))))

(t/deftest does-not-cache-nil-returns
  (let [[memoed-fn _v c] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= nil (memoed-fn "return-nil" "blarg")))
      (t/is (= 1 @c))
      (t/is (= nil (memoed-fn "return-nil" "blarg")))
      (t/is (= 2 @c)))))

(t/deftest handles-throw-from-raw-fn
  (let [[memoed-fn _v c] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (thrown? ExceptionInfo (memoed-fn "throw" "blarg")))
      (t/is (= 1 @c))
      (t/is (thrown? ExceptionInfo (memoed-fn "throw" "blarg")))
      (t/is (= 2 @c)))))

(t/deftest refreshes_after_ttl
  (let [[memoed-fn v c] (get-memoed-for-test cache-config)
        fake-time (atom "2019-07-25T10:30:45.00Z")]
    (with-redefs [c/instant-now (fn [] (Instant/parse @fake-time))]
      (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
      (t/is (= 1 @c))
      (reset! v "new")
      (reset! fake-time "2019-07-25T10:30:47.00Z")
      (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
      (t/is (= 1 @c))
      (reset! fake-time "2019-07-25T10:30:47.01Z")
      (t/is (= 'refresh-me!new (memoed-fn "refresh" "me")))
      (t/is (= 2 @c)))))

(t/deftest can-explicitly-clear-an-item
  (let [[memoed-fn v c] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'explicit-clear!original (memoed-fn "explicit" "clear")))
      (t/is (= 1 @c))
      (reset! v "new")
      (memo/memo-clear! memoed-fn '("explicit" "clear"))
      (t/is (= 'explicit-clear!new (memoed-fn "explicit" "clear")))
      (t/is (= 2 @c)))))

(t/deftest config-without-ttl-does-not-auto-refresh
  (let [[memoed-fn v c] (get-memoed-for-test (dissoc cache-config :ttl))
        fake-time (atom "2019-07-25T10:30:45.00Z")]
    (with-redefs [c/instant-now (fn [] (Instant/parse @fake-time))]
      (t/is (= 'never-refresh!original (memoed-fn "never" "refresh")))
      (t/is (= 1 @c))
      (reset! v "new")
      (reset! fake-time "2030-07-25T10:30:47.01Z")
      (t/is (= 'never-refresh!original (memoed-fn "never" "refresh")))
      (t/is (= 1 @c)))))

(t/deftest can-explicitly-clear-all-items
  (let [[memoed-fn v c] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'clear-all!original (memoed-fn "clear" "all")))
      (t/is (= 'the-things!original (memoed-fn "the" "things")))
      (t/is (= 2 @c))
      (reset! v "new")
      (memo/memo-clear! memoed-fn)
      (t/is (= 'clear-all!new (memoed-fn "clear" "all")))
      (t/is (= 'the-things!new (memoed-fn "the" "things")))
      (t/is (= 4 @c)))))

(t/deftest preserves-cache-on-new-memoize
  (let [[memoed-fn _v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (t/is (= 'preserve-me!original (memoed-fn "preserve" "me")))))
  (let [[memoed-fn v] (get-memoed-for-test cache-config)]
    (with-redefs [c/instant-now (fn [] (Instant/parse "2019-07-25T10:15:30.00Z"))]
      (reset! v "new")
      (t/is (= 'preserve-me!original (memoed-fn "preserve" "me"))))))

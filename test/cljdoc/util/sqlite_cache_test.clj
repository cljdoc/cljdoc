(ns cljdoc.util.sqlite-cache-test
  (:require [cljdoc.util.sqlite-cache :as c]
            [clojure.core.memoize :as memo]
            [clojure.java.io :as io]
            [clojure.test :as t])
  (:import [clojure.lang ExceptionInfo]
           [java.time Clock Instant ZoneOffset]))

(defn fixed-clock [instant-str]
  (Clock/fixed (Instant/parse instant-str) ZoneOffset/UTC))

(defn fake-clock [instant-str]
  (atom (fixed-clock instant-str)))

(defn reset-fake-clock [clock instant-str]
  (reset! clock (fixed-clock instant-str)))

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
  (io/make-parents database-filename)
  (when (.exists (io/file database-filename))
    (io/delete-file database-filename))
  (f))

(t/use-fixtures :once wrap-delete-db-before)

(t/deftest caches
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= 'cache-me!original (memoed-fn "cache" "me")))
    (t/is (= 1 @c))
    (reset! v "new")
    (t/is (= 'cache-me!original (memoed-fn "cache" "me")))
    (t/is (= 1 @c))))

(t/deftest does-not-cache-nil-returns
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn _v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= nil (memoed-fn "return-nil" "blarg")))
    (t/is (= 1 @c))
    (t/is (= nil (memoed-fn "return-nil" "blarg")))
    (t/is (= 2 @c))))

(t/deftest handles-throw-from-raw-fn
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn _v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (thrown? ExceptionInfo (memoed-fn "throw" "blarg")))
    (t/is (= 1 @c))
    (t/is (thrown? ExceptionInfo (memoed-fn "throw" "blarg")))
    (t/is (= 2 @c))))

(t/deftest refreshes_after_ttl
  (let [clock (fake-clock "2019-07-25T10:30:45.00Z")
        [memoed-fn v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
    (t/is (= 1 @c))
    (reset! v "new")
    (reset-fake-clock clock "2019-07-25T10:30:47.00Z")
    (t/is (= 'refresh-me!original (memoed-fn "refresh" "me")))
    (t/is (= 1 @c))
    (reset-fake-clock clock "2019-07-25T10:30:47.01Z")
    (t/is (= 'refresh-me!new (memoed-fn "refresh" "me")))
    (t/is (= 2 @c))))

(t/deftest can-explicitly-clear-an-item
  (let [clock (fake-clock "2019-07-25T10:30:45.00Z")
        [memoed-fn v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= 'explicit-clear!original (memoed-fn "explicit" "clear")))
    (t/is (= 1 @c))
    (reset! v "new")
    (memo/memo-clear! memoed-fn '("explicit" "clear"))
    (t/is (= 'explicit-clear!new (memoed-fn "explicit" "clear")))
    (t/is (= 2 @c))))

(t/deftest config-without-ttl-does-not-auto-refresh
  (let [clock (fake-clock "2019-07-25T10:30:45.00Z")
        [memoed-fn v c] (get-memoed-for-test (-> cache-config
                                                 (dissoc :ttl)
                                                 (assoc :clock clock)))]
    (t/is (= 'never-refresh!original (memoed-fn "never" "refresh")))
    (t/is (= 1 @c))
    (reset! v "new")
    (reset-fake-clock clock "2030-07-25T10:30:47.01Z")
    (t/is (= 'never-refresh!original (memoed-fn "never" "refresh")))
    (t/is (= 1 @c))))

(t/deftest can-explicitly-clear-all-items
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn v c] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= 'clear-all!original (memoed-fn "clear" "all")))
    (t/is (= 'the-things!original (memoed-fn "the" "things")))
    (t/is (= 2 @c))
    (reset! v "new")
    (memo/memo-clear! memoed-fn)
    (t/is (= 'clear-all!new (memoed-fn "clear" "all")))
    (t/is (= 'the-things!new (memoed-fn "the" "things")))
    (t/is (= 4 @c))))

(t/deftest preserves-cache-on-new-memoize
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn _v] (get-memoed-for-test (assoc cache-config :clock clock))]
    (t/is (= 'preserve-me!original (memoed-fn "preserve" "me"))))
  (let [clock (fake-clock "2019-07-25T10:15:30.00Z")
        [memoed-fn v] (get-memoed-for-test (assoc cache-config :clock clock))]
    (reset! v "new")
    (t/is (= 'preserve-me!original (memoed-fn "preserve" "me")))))

(ns cljdoc.util.with-retries-test
  (:require [cljdoc.util.with-retries :as wr]
            [clojure.test :as t]))

(defn ->call-counting-fn
  [return-value]
  (let [call-count (atom 0)]
    (fn [& args]
      (if (= (first args) :call-count)
        @call-count
        (do
          (swap! call-count inc)
          return-value)))))

(t/deftest with-retries-test
  (binding [wr/*default-delay* 1]
    (t/testing "does not retry bodies that succeed"
      (let [f (->call-counting-fn :value)]
        (wr/with-retries {} (f))
        (t/is (= 1 (f :call-count)))))
    (t/testing "returns the body's return value"
      (let [f (->call-counting-fn :value)]
        (t/is (= :value (wr/with-retries {} (f))))))
    (t/testing "retries bodies that fail, re-raising the exception by default"
      (let [f (->call-counting-fn :value)]
        (t/is (thrown? Exception (wr/with-retries {}
                                   (f)
                                   (throw (Exception. "oops")))))
        (t/is (= 3 (f :call-count)))))
    (t/testing "calls a function on retry"
      (let [on-retry (->call-counting-fn :retry)]
        (t/is (thrown? Exception (wr/with-retries {:on-retry on-retry}
                                   (throw (Exception. "oops")))))
        (t/is (= 2 (on-retry :call-count)))))
    (t/testing "calls a function on failure and returns nil"
      (let [on-failure (->call-counting-fn :retry)]
        (t/is (nil? (wr/with-retries {:on-failure on-failure}
                      (throw (Exception. "oops")))))
        (t/is (= 1 (on-failure :call-count)))))
    (t/testing "can retry on a specific list of exception types"
      (let [retry-on [ArithmeticException]
            f1 (->call-counting-fn :f1)
            f2 (->call-counting-fn :f2)]
        (t/is (thrown? Exception (wr/with-retries {:retry-on retry-on}
                                   (f1)
                                   (throw (Exception. "oops")))))
        (t/is (= 1 (f1 :call-count)))
        (t/is (thrown? ArithmeticException (wr/with-retries {:retry-on retry-on}
                                             (f2)
                                             (/ 1 0))))
        (t/is (= 3 (f2 :call-count)))))))

(ns cljdoc.util.datetime-test
  (:require [cljdoc.util.datetime :as dt]
            [clojure.test :as t]))

(t/deftest day-suffix-test
  (t/is (= "st" (dt/day-suffix 1)))
  (t/is (= "nd" (dt/day-suffix 2)))
  (t/is (= "rd" (dt/day-suffix 3)))
  (t/is (= "th" (dt/day-suffix 15))))

(t/deftest analytics-format-test
  (t/is (= "Wed, Oct 17th" (dt/->analytics-format "2018-10-17T20:58:21.491730Z"))))

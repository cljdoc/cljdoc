(ns cljdoc.util.datetime-test
  (:require [cljdoc.util.datetime :as dt]
            [clojure.test :as t]))

(t/deftest day-suffix-test
  (t/is (= "st" (dt/day-suffix 1)))
  (t/is (= "nd" (dt/day-suffix 2)))
  (t/is (= "rd" (dt/day-suffix 3)))
  (t/is (= "th" (dt/day-suffix 4)))
  (t/is (= "th" (dt/day-suffix 11)))
  (t/is (= "th" (dt/day-suffix 12)))
  (t/is (= "th" (dt/day-suffix 13)))
  (t/is (= "th" (dt/day-suffix 14)))
  (t/is (= "th" (dt/day-suffix 15)))
  (t/is (= "st" (dt/day-suffix 21)))
  (t/is (= "nd" (dt/day-suffix 22)))
  (t/is (= "rd" (dt/day-suffix 23)))
  (t/is (= "th" (dt/day-suffix 24)))
  (t/is (= "st" (dt/day-suffix 31))))

(t/deftest human-short-format-test
  (t/is (= "Mon, Oct 22nd" (dt/human-short "2018-10-22T20:58:21.491730Z"))))

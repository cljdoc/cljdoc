(ns cljdoc.util.datetime
  "Helpers function to work with dates and times."
  (:require [clj-time.core :as t]
            [clj-time.format :as time-fmt]))

(def analytics-format (time-fmt/formatter "EEE, MMM dd"))

(defn day-suffix
  "Append approptiate suffix based on day of the month.

  Eg: 1st, 2nd, 3rd & 20th."
  [day]
  (case day
    1 "st"
    2 "nd"
    3 "rd"
    "th"))

(defn ->analytics-format
  "Truns timestamp to a nice, human readable date format."
  [timestamp]
  (let [datetime (time-fmt/parse timestamp)]
    (str (time-fmt/unparse analytics-format datetime)
         (day-suffix (t/day  datetime)))))


(comment

  (day-suffix 21)
  (->analytics-format "2018-10-17T20:58:21.491730Z")
  )

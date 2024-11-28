(ns cljdoc.util.datetime
  "Helpers function to work with dates and times."
  (:import (java.time Instant ZoneId)
           (java.time.format DateTimeFormatter)
           (java.util Locale)))

(defn day-suffix
  "Append approptiate suffix based on day of the month.

  Eg: 1st, 2nd, 3rd & 20th."
  [day]
  (if (<= 11 day 13)
    "th"
    (case (mod day 10)
      1 "st"
      2 "nd"
      3 "rd"
      "th")))

(defn ->analytics-format
  "Turns timestamp to a nice, human readable date format."
  [ts]
  (let [datetime (Instant/parse ts)
        utc      (ZoneId/of "UTC")]
    (str (.format (.withZone (DateTimeFormatter/ofPattern "EEE, MMM dd" Locale/ENGLISH) utc) datetime)
         (day-suffix (.getDayOfMonth (.atZone datetime utc))))))

(comment
  (day-suffix 21)
  ;; => "th"

  (->analytics-format "2018-10-17T20:58:21.491730Z"))

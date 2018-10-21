(ns cljdoc.util.datetime
  "Helpers function to work with dates and times."
  (:import (java.time Instant Duration ZoneId)
           (java.time.format DateTimeFormatter)))

(defn day-suffix
  "Append approptiate suffix based on day of the month.

  Eg: 1st, 2nd, 3rd & 20th."
  [day]
  (get {1 "st" 2 "nd" 3 "rd"} day "th"))

(defn ->analytics-format
  "Turns timestamp to a nice, human readable date format."
  [ts]
  (let [datetime (Instant/parse ts)]
    (str (.format (.withZone (DateTimeFormatter/ofPattern "EEE, MMM dd")
                             (ZoneId/systemDefault)) datetime)
         (day-suffix (.getDayOfMonth
                      (.atZone datetime
                               (ZoneId/systemDefault)))))))

(comment

  (day-suffix 21)
  (->analytics-format "2018-10-17T20:58:21.491730Z")
  )

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

(defn human-short
  "Turns timestamp to a nice, human readable date format."
  [ts]
  (let [datetime (Instant/parse ts)
        utc      (ZoneId/of "UTC")]
    (str (.format (.withZone (DateTimeFormatter/ofPattern "EEE, MMM dd" Locale/ENGLISH) utc) datetime)
         (day-suffix (.getDayOfMonth (.atZone datetime utc))))))

(defn timestamp [datetime-str]
  (let [d (java.time.ZonedDateTime/parse datetime-str)]
    (.format d (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.S"))))

(comment
  (day-suffix 21)
  ;; => "st"

  (->analytics-format "2018-10-22T11:12:13.12313Z")
  ;; => "Mon, Oct 22nd"

  ;; doesn't round fractional seconds, truncates, that's ok for our usage
  (timestamp "2018-10-17T20:58:21.491730Z")
  ;; => "2018-10-17 20:58:21.4"

  (timestamp "2018-10-17T20:58:21.411730Z")
  ;; => "2018-10-17 20:58:21.4"

  :eoc)

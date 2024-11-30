(ns cljdoc.util.datetime
  "Helpers function to work with dates and times."
  (:import (java.time LocalDate)
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

(defn date->human-short
  "Turns timestamp to a nice, human readable date format."
  [date-str]
  (let [date (LocalDate/parse date-str)]
    (str (.format (DateTimeFormatter/ofPattern "EEE, MMM d" Locale/ENGLISH) date)
         (day-suffix (.getDayOfMonth date)))))

(defn date->human-long
  [date-str]
  (let [date (LocalDate/parse date-str)]
    (str (.format (DateTimeFormatter/ofPattern "EEEE, MMMM d" Locale/ENGLISH) date)
         (day-suffix (.getDayOfMonth date))
         ", "
         (.format (DateTimeFormatter/ofPattern "yyyy" Locale/ENGLISH) date))))

(defn datetime->timestamp [datetime-str]
  (let [d (java.time.ZonedDateTime/parse datetime-str)]
    (.format d (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss.S"))))

(defn valid-date? [yyyymmdd]
  (boolean (try
             (.parse (DateTimeFormatter/ofPattern "yyyy-MM-dd") yyyymmdd)
             (catch Throwable _ex))))

(comment
  (valid-date? "2024-01-31")
  ;; => true

  (valid-date? "2024-01-32")
  ;; => false

  (day-suffix 21)
  ;; => "st"

  (date->human-short "2018-10-22")
  ;; => "Mon, Oct 22nd"

  (date->human-long "2018-10-22")
  ;; => "Monday, October 22nd, 2018"

  ;; doesn't round fractional seconds, truncates, that's ok for our usage
  (datetime->timestamp "2018-10-17T20:58:21.491730Z")
  ;; => "2018-10-17 20:58:21.4"

  (datetime->timestamp "2018-10-17T20:58:21.411730Z")
  ;; => "2018-10-17 20:58:21.4"

  :eoc)

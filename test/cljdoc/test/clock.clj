(ns cljdoc.test.clock
  (:import [java.time Clock Instant ZoneOffset]))

(defn fixed-clock [instant-str]
  (Clock/fixed (Instant/parse instant-str) ZoneOffset/UTC))

(defn fake-clock [instant-str]
  (atom (fixed-clock instant-str)))

(defn reset-fake-clock [clock instant-str]
  (reset! clock (fixed-clock instant-str)))

(ns cljdoc.render.badge
  "Algorithms and strategy transcribed from https://github.com/badgen/badgen
   Copyright 2018 Amio
   Permission to use  copy  modify  and/or distribute this software for any purpose with or without fee is hereby granted  provided that the above copyright notice and this permission notice appear in all copies.
   THE SOFTWARE IS PROVIDED \"AS IS\" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL  DIRECT  INDIRECT  OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE  DATA OR PROFITS  WHETHER IN AN ACTION OF CONTRACT  NEGLIGENCE OR OTHER TORTIOUS ACTION  ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [hiccup2.core :as hiccup]))

(def ^:private widths-verdana-110
  (-> "widths-verdana-110.edn"
      io/resource
      slurp
      edn/read-string
      ;; we are memory constrained, load into int array to save some
      int-array))

;; use the @ character width as a fallback
(def ^:private fallback-width (get widths-verdana-110 (int \@)))

(defn- calc-width [text]
  (transduce
   (map (fn [c] (get widths-verdana-110 (int c) fallback-width)))
   +
   text))

;; we only use two colors at this point
(def ^:private colors {:blue "#08C"
                       :red "#E43"})

;; these are constant, no need to recalc every time
(def ^:private label "cljdoc")
(def ^:private label-text-width (calc-width label))

(defn cljdoc-badge
  "Render badge [ cljdoc | `badge-text` ] with `badge-status`.
  If `badge-status` is `:success`, badge text background renders blue, else red."
  ^String [badge-text badge-status]
  (let [label-start-text 50
        badge-text-width (calc-width badge-text)
        label-rect-width (+ label-text-width 100)
        badge-text-rect-width (+ badge-text-width 100)
        badge-width (+ label-rect-width badge-text-rect-width)
        gradient-id "gradid"
        mask-id "maskid"
        status-color (if (= :success badge-status)
                       (:blue colors)
                       (:red colors))
        accessible-text (str label ": " badge-text)]
    (str (hiccup/html [:svg {:width (/ badge-width 10)
                             :height 20
                             :viewBox (str "0 0 " badge-width " 200")
                             :xmlns "http://www.w3.org/2000/svg"
                             :role "img"
                             :aria-label accessible-text}
                       [:title accessible-text]
                       [:linearGradient {:id gradient-id :x2 0 :y2 "100%"}
                        [:stop {:offset "0" :stop-opacity ".1" :stop-color "#EEE"}]
                        [:stop {:offset "1" :stop-opacity ".1"}]]
                       [:mask {:id mask-id}
                        [:rect {:width badge-width :height "200" :rx 30 :fill "#FFF"}]]
                       [:g {:mask (str "url(#" mask-id ")")}
                        [:rect {:width label-rect-width :height "200" :fill "#555" :x 0}]
                        [:rect {:width badge-text-rect-width :height "200" :fill status-color :x label-rect-width}]
                        [:rect {:width badge-width :height 200 :fill (str "url(#" gradient-id ")")}]]
                       [:g {:aria-hidden true :fill "#fff" :text-anchor "start"
                            :font-family "Verdana,DejaVu Sans,sans-serif" :font-size 110}
                        [:text {:x (+ label-start-text 10) :y 148 :textLength label-text-width :fill "#000" :opacity "0.25"} label]
                        [:text {:x label-start-text        :y 138 :textLength label-text-width} label]
                        [:text {:x (+ label-rect-width 55) :y 148 :textLength badge-text-width :fill "#000" :opacity "0.25"} badge-text]
                        [:text {:x (+ label-rect-width 45) :y 138 :textLength badge-text-width} badge-text]]]))))

(comment
  (cljdoc-badge "Hey there" :success)
  ;; => "<svg aria-label=\"cljdoc: Hey there\" height=\"20\" role=\"img\" viewBox=\"0 0 1063 200\" width=\"106.3\" xmlns=\"http://www.w3.org/2000/svg\"><title>cljdoc: Hey there</title><linearGradient id=\"gradid\" x2=\"0\" y2=\"100%\"><stop offset=\"0\" stop-color=\"#EEE\" stop-opacity=\".1\"></stop><stop offset=\"1\" stop-opacity=\".1\"></stop></linearGradient><mask id=\"maskid\"><rect fill=\"#FFF\" height=\"200\" rx=\"30\" width=\"1063\"></rect></mask><g mask=\"url(#maskid)\"><rect fill=\"#555\" height=\"200\" width=\"418\" x=\"0\"></rect><rect fill=\"#E43\" height=\"200\" width=\"645\" x=\"418\"></rect><rect fill=\"url(#gradid)\" height=\"200\" width=\"1063\"></rect></g><g aria-hidden=\"aria-hidden\" fill=\"#fff\" font-family=\"Verdana,DejaVu Sans,sans-serif\" font-size=\"110\" text-anchor=\"start\"><text fill=\"#000\" opacity=\"0.25\" textLength=\"318\" x=\"60\" y=\"148\">cljdoc</text><text textLength=\"318\" x=\"50\" y=\"138\">cljdoc</text><text fill=\"#000\" opacity=\"0.25\" textLength=\"545\" x=\"473\" y=\"148\">Hey there</text><text textLength=\"545\" x=\"463\" y=\"138\">Hey there</text></g></svg>"

  (calc-width "Hello, World!")
  ;; => 721

  (reduce max widths-verdana-110)
  ;; => 290

  (->> widths-verdana-110
       (map-indexed vector)
       (sort-by second)
       reverse
       (mapv (fn [[idx width]]
               {:code-point (format "U+%04X" idx)
                :width width}))
       (take 10))
  ;; => ({:code-point "U+0604", :width 290}
  ;;     {:code-point "U+102A", :width 240}
  ;;     {:code-point "U+0BCC", :width 230}
  ;;     {:code-point "U+0FD0", :width 220}
  ;;     {:code-point "U+0DDE", :width 220}
  ;;     {:code-point "U+0DDB", :width 220}
  ;;     {:code-point "U+17D8", :width 200}
  ;;     {:code-point "U+0DF2", :width 200}
  ;;     {:code-point "U+0D8E", :width 200}
  ;;     {:code-point "U+0D4C", :width 200})

  (calc-width "\u0604")
  ;; => 290

  :eoc)

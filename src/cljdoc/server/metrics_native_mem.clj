(ns cljdoc.server.metrics-native-mem
  "To enable native memory stats add the following jvm opt to your startup:

     -XX:NativeMemoryTracking=summary

  The native memory stats output from the JVM is semi-structured text with many
  syntactic inconsistencies making it challenging to parse.

  The output format syntax is not documented nor versioned making parsing potentially brittle.

  JDK source that emits the output:
  https://github.com/openjdk/jdk/blob/jdk-23%2B37/src/hotspot/share/nmt/memReporter.cpp "
  (:require [clojure.string :as str])
  (:import (java.lang.management ManagementFactory)
           (javax.management ObjectName)))

(set! *warn-on-reflection* true)

(defn- get-native-memory-metrics-text
  "This is equivalent to the output from running:

  jcmd <java process pid>  VM.native_memory summary

  It avoids a spawn of jcmd."
  []
  (.invoke
   (ManagementFactory/getPlatformMBeanServer)
   (ObjectName. "com.sun.management:type=DiagnosticCommand")
   "vmNativeMemory"
   (into-array Object [(into-array String ["summary"])])
   (into-array String ["[Ljava.lang.String;"])))

(defn- parse-line [line]
  (let [indent (if (str/blank? line)
                 -1
                 (count (re-find #"^-? *\(? *" line)))
        line (str/lower-case line)
        ;; join foo bar -> foo-bar
        line (str/replace line #"([a-z]) ([a-z])" "$1-$2")
        [group metrics-data] (if (str/starts-with? line "-")
                               (rest (re-find #"^- +(.*) +(\(.*)" line))
                               [nil (str/trim line)])
        group (keyword group)
        metrics-data (str/replace metrics-data #"[(,)]" "")
        [prefix metrics] (rest (re-find #" *(?:(.*):)? *(.*)" metrics-data))
        prefix (keyword prefix)
        path (->> [group prefix]
                  (keep identity)
                  (into []))
        tokens (str/split metrics #"[ =]")
        m (loop [tokens tokens
                 metric-keys []
                 res {}]
            (if (not (seq tokens))
              res
              (let [[t1] tokens
                    [val-key val] (cond
                                    (= "at-peak" t1)
                                    [:at-peak true]
                                    (str/ends-with? t1 "kb")
                                    [:kb (parse-long (subs t1 0 (- (count t1) 2)))]
                                    (str/starts-with? t1 "#")
                                    [:cnt (parse-long (subs t1 1))])]
                (recur
                 (rest tokens)
                 (if val-key
                   metric-keys
                   (let [k (keyword t1)]
                     ;; peak=vals should always be a subkey of the last metric
                     (if (= :peak k)
                       (conj metric-keys k)
                       [k])))
                 (if val-key
                   (assoc-in res
                             (if (seq metric-keys)
                               (conj (into path metric-keys) val-key)
                               (conj path val-key))
                             val)
                   res)))))]
    ;; [group] [prefix] metric metric..
    {:indent indent
     :group group
     :metrics-data metrics-data
     :prefix prefix
     :metrics metrics
     :path path
     :m m}))

(defn- strip-header-lines [input]
  (->> (str/split-lines input)
       (drop-while #(not (str/starts-with? % "Total:")))
       (str/join "\n")))

(defn- find-path [parsed ndx p]
  (loop [ps (reverse (subvec parsed 0 ndx))
         indent (:indent p)
         res (list)]
    (let [pf (first ps)
          indent-next (:indent pf)]
      (cond
        (or (not pf) (< indent-next 0))
        res
        (< indent-next indent)
        (recur (rest ps)
               (:indent pf)
               (cons (or (-> pf :m first first)
                         (-> pf :path first))
                     res))
        :else
        (recur (rest ps)
               indent
               res)))))

(defn- parse-block [block]
  (let [lines (str/split-lines block)
        parsed (mapv parse-line lines)]
    (loop [ndx 0
           res {}]
      (let [p (get parsed ndx)]
        (cond
          (not p)
          res
          (= -1 (:indent p)) ;; blank line
          (recur (inc ndx) res)
          :else
          (let [path (find-path parsed ndx p)]
            (recur (inc ndx)
                   (if (seq path)
                     (update-in res path merge (:m p))
                     (merge res (:m p))))))))))

(defn parse-output-text [input]
  (let [input (strip-header-lines input)
        blocks (str/split input #"(?s)\n *\n")]
    (mapv parse-block blocks)))

(defn metrics []
  (parse-output-text (get-native-memory-metrics-text)))

(comment
  (println (get-native-memory-metrics-text))

  (metrics)

  (parse-output-text (slurp "fiddle/jcmd.out"))

  :eoc)

(ns cljdoc.migration-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [me.raynes.fs :as fs]))

(t/deftest migration-names-test
  (let [names (reduce
               (fn [names file]
                 (let [base (fs/base-name (.getPath file))]
                   (update names (first (str/split base #"-")) (fnil conj #{}) base)))
               {}
               (fs/list-dir (io/resource "migrations")))]
    (t/is (every?
           true?
           (map-indexed
            (fn [i prefix]
              (let [base (-> prefix
                             names
                             first
                             (str/replace #"\.(up|down)\.sql$" ""))
                    expected-names (set (map #(str base "." % ".sql") ["up" "down"]))]
                (and (= (inc i) (try (Long/parseLong prefix) (catch Exception _e)))
                     (= (names prefix) expected-names))))
            (sort (keys names)))))))

(comment
  (t/run-tests))

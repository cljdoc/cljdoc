(ns cljdoc.migration-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [me.raynes.fs :as fs]))

(defn file-extension [s]
  (second (re-find #"(\.[a-zA-Z0-9]+)$" s)))

(t/deftest migration-names-test
  (let [names (->> (fs/list-dir (io/resource "migrations"))
                   (group-by #(file-extension (.getName %)))
                   (map
                    (fn [[extension files]]
                      (reduce
                       (fn [names file]
                         (let [base (fs/base-name (.getPath file))]
                           (update names
                                   (first (str/split base (case extension ".sql" #"-" ".clj" #"_")))
                                   (fnil conj #{}) base)))
                       {}
                       files)))
                   (apply merge))]
    (t/is (every?
           true?
           (map-indexed
            (fn [i prefix]
              (let [file-name (-> prefix names first)]
                (and
                 (= (inc i) (try (Long/parseLong prefix) (catch Exception _e)))
                 (case (file-extension file-name)
                   ".sql" (let [base (str/replace file-name #"\.(up|down)\.sql$" "")
                                expected-names (set (map #(str base "." % ".sql") ["up" "down"]))]
                            (= (names prefix) expected-names))
                   ".clj" (let [migration-code (slurp (str "resources/migrations/" file-name))]
                            (and (str/includes? migration-code "up") (str/includes? migration-code "down")))))))
            (sort (keys names)))))))

(comment
  (t/run-tests))

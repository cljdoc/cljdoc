(ns cljdoc.migration-test
  "A lint, of sorts, for ragtime database migration filenames"
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [me.raynes.fs :as fs]))

(defn- migration-files []
  (->> (fs/list-dir (io/resource "migrations"))
       (mapv #(fs/base-name (.getPath %)))))

(t/deftest migrations-are-clj-or-sql
  (doseq [mfile (migration-files)]
    (t/is (or (str/ends-with? mfile ".clj")
              (str/ends-with? mfile ".sql"))
          mfile)))

(t/deftest migration-nums-are-sequential-and-have-no-gaps
  (let [migration-nums (->> (migration-files)
                            (mapv #(let [re-sep (if (str/ends-with? % ".sql") #"-" #"_")]
                                     (first (str/split % re-sep))))
                            distinct
                            sort)
        expected-nums (->> (range 1 (inc (count migration-nums)))
                           (mapv #(format "%03d" %)))]
    (t/is (= expected-nums migration-nums))))

(t/deftest sql-migrations-have-up-and-down
  (let [sql-migrations (->> (migration-files)
                            (filter #(str/ends-with? % ".sql"))
                            (mapv #(rest (re-find #"(.+)\.(.+?).sql" %)))
                            (reduce (fn [acc [mbase dir]]
                                      (update acc mbase (fnil conj #{}) dir))
                                    {}))]
    (doseq [[mbase dirs] sql-migrations]
      (t/is (= #{ "up" "down"} dirs) mbase))))

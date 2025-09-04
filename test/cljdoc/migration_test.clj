(ns cljdoc.migration-test
  "A lint, of sorts, for ragtime database migration filenames, at least in the way cljdoc uses them."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :as t]
            [ragtime.next-jdbc :as ragtime-next-jdbc]))

(defn- migration-files []
  (->> (fs/list-dir (io/resource "migrations"))
       (mapv fs/file-name)))

(t/deftest valid-migration-file-exts-test
  (doseq [mfile (migration-files)]
    (t/is (re-find #"\.(clj|sql|edn)$" mfile)
          mfile)))

(t/deftest migration-nums-are-sequential-and-have-no-gaps
  (let [migration-nums (->> (migration-files)
                            (filterv #(re-find #"\.(clj|sql)$" %))
                            (mapv #(let [re-sep (if (str/ends-with? % ".sql") #"-" #"_")]
                                     (first (str/split % re-sep))))
                            distinct
                            sort)
        expected-nums (->> (range 1 (inc (count migration-nums)))
                           (mapv #(format "%03d" %)))]
    (t/is (= expected-nums migration-nums))))

(t/deftest migrations-have-up-and-down
  (let [migrations (ragtime-next-jdbc/load-resources "migrations")]
    (doseq [{:keys [up down] :as m} migrations]
      (t/is (or (var? up) (seq up))
            (str "up in: " (pr-str m)))
      (t/is (or (var? down) (seq down))
            (str "down in: " (pr-str m))))))

(t/deftest migrations-correspond-to-source-files-test
  (let [loaded-migrations (->> (ragtime-next-jdbc/load-resources "migrations")
                               (reduce (fn [acc {:keys [id] :as n}]
                                         (assoc acc
                                                ;; compensate for historical ragtime-clj migration
                                                (if (str/starts-with? id "010_")
                                                  (str/replace id "_" "-")
                                                  id)
                                                n))
                                       {}))
        file-migration-ids (->> (migration-files)
                                (keep #(some-> (re-find #"^(.*)(\.clj|(\.up|\.down)\.sql)$" %)
                                               second
                                               (str/replace "_" "-")))
                                distinct
                                sort)]
    (doseq [id file-migration-ids]
      (t/is (get loaded-migrations id)
            (str "found migration with id " id)))))

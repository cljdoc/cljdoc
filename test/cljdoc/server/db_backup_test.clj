(ns cljdoc.server.db-backup-test
  (:require [babashka.fs :as fs]
            [babashka.process :as p]
            [cljdoc.s3 :as s3]
            [cljdoc.server.db-backup :as db-backup]
            [clojure.test :as t]
            [clojure.tools.logging :as log]
            [matcher-combinators.test])
  (:import (java.lang AutoCloseable)
           (java.time LocalDateTime)))

(set! *warn-on-reflection* true)

;; {"objkey" {:file "filename"}}
(defrecord FakeObjectStore [fake-store]
  s3/IObjectStore AutoCloseable
  (list-objects [_]
    (->> @fake-store
         keys
         (mapv (fn [k] {:key k}))))
  (delete-object [_ object-key]
    (swap! fake-store dissoc object-key))
  (put-object [_ object-key from-file]
    (swap! fake-store assoc
           object-key
           {:file from-file}))
  (copy-object [_  source-key dest-key]
    (let [source (get @fake-store source-key)]
      (swap! fake-store assoc
             dest-key
             source)))
  (get-object [_ object-key to-file]
    (let [{:keys [file]} (get @fake-store object-key)]
      (println "file->" file)
      (fs/copy file to-file {:replace-existing true})))
  (close [_]))

(comment
  (def fake-store (atom {}))

  (def object-store (FakeObjectStore. fake-store))

  (s3/list-objects object-store)

  (spit "target/some-file" "foo")

  (s3/put-object object-store "daily/foo" "target/some-file")

  (s3/get-object object-store "daily/foo" "target/dest-file")

  (s3/copy-object object-store "daily/foo" "daily/foo2")

  @fake-store

  :eoc)

(defn parse-datetime [s]
  (LocalDateTime/parse s))

(defn list-backups [fake-store]
  (->> @fake-store
       keys
       sort
       (group-by (fn [k] (-> (re-matches #"(.*?)/.*" k)
                             second
                             keyword)))))

(t/deftest backups-test
  (let [fake-store (atom {})
        opts {:now-fn #(parse-datetime "2024-09-13T11:12:13.12345")
              :object-store-fn (fn [_opts] (FakeObjectStore. fake-store))
              :db-spec {:dbtype "sqlite"
                        :host :none
                        :dbname "target/backups-test/cljdoc.db.sqlite"
                        :synchronous "NORMAL"
                        :journal_mode "WAL"}
              :cache-db-spec {:dbtype "sqlite"
                              :host :none
                              :dbname "target/backups-test/cache.db"}}
        initial-expected-backups {:daily ["daily/cljdoc-db-2024-09-13_2024-09-13T11-12-13.tar.zst"]
                                  :weekly ["weekly/cljdoc-db-2024-09-09_2024-09-13T11-12-13.tar.zst"]
                                  :monthly ["monthly/cljdoc-db-2024-09-01_2024-09-13T11-12-13.tar.zst"]
                                  :yearly ["yearly/cljdoc-db-2024-01-01_2024-09-13T11-12-13.tar.zst"]}]
    (t/testing "On 2024-09-13, no backups exist"
      (db-backup/backup-job! opts)
      (t/is (= initial-expected-backups (list-backups fake-store))))
    (t/testing "backing up again on same day should be no-op"
      (db-backup/backup-job! (assoc opts :now-fn #(parse-datetime "2024-09-13T23:59:59")))
      (t/is (= initial-expected-backups (list-backups fake-store))))
    (t/testing "backup on next day results in new daily only"
      (db-backup/backup-job! (assoc opts :now-fn #(parse-datetime "2024-09-14T00:45:22")))
      (t/is (= (update initial-expected-backups :daily conj
                       "daily/cljdoc-db-2024-09-14_2024-09-14T00-45-22.tar.zst")
               (list-backups fake-store))))
    (t/testing "2 more backups brings us to Monday and our next weekly backup"
      (doseq [day [15 16]]
        (db-backup/backup-job! (assoc opts :now-fn #(parse-datetime (format "2024-09-%dT00:45:%d"
                                                                            day day)))))
      (t/is (= (-> initial-expected-backups
                   (update :daily conj
                           "daily/cljdoc-db-2024-09-14_2024-09-14T00-45-22.tar.zst"
                           "daily/cljdoc-db-2024-09-15_2024-09-15T00-45-15.tar.zst"
                           "daily/cljdoc-db-2024-09-16_2024-09-16T00-45-16.tar.zst")
                   (update :weekly conj
                           "weekly/cljdoc-db-2024-09-16_2024-09-16T00-45-16.tar.zst"))
               (list-backups fake-store))))
    (t/testing "After 8 daily backups we prune daily backups to a count of 7"
      (doseq [day [17 18 19 20]]
        (db-backup/backup-job! (assoc opts :now-fn #(parse-datetime (format "2024-09-%dT00:45:%d"
                                                                            day day)))))
      (t/is (= (-> initial-expected-backups
                   (assoc :daily
                          ["daily/cljdoc-db-2024-09-14_2024-09-14T00-45-22.tar.zst"
                           "daily/cljdoc-db-2024-09-15_2024-09-15T00-45-15.tar.zst"
                           "daily/cljdoc-db-2024-09-16_2024-09-16T00-45-16.tar.zst"
                           "daily/cljdoc-db-2024-09-17_2024-09-17T00-45-17.tar.zst"
                           "daily/cljdoc-db-2024-09-18_2024-09-18T00-45-18.tar.zst"
                           "daily/cljdoc-db-2024-09-19_2024-09-19T00-45-19.tar.zst"
                           "daily/cljdoc-db-2024-09-20_2024-09-20T00-45-20.tar.zst"])
                   (update :weekly conj
                           "weekly/cljdoc-db-2024-09-16_2024-09-16T00-45-16.tar.zst"))
               (list-backups fake-store))))
    (t/testing "After daily backups for until 1st day of 2026"
      (let [start-date (parse-datetime "2024-09-20T00:11:12")
            cutoff-date (parse-datetime "2026-01-02T00:00:00")]
        (doseq [date (->> (iterate (fn [^LocalDateTime d] (.plusDays d 1)) start-date)
                          (take-while (fn [^LocalDateTime d] (.isBefore d cutoff-date))))]
          (db-backup/backup-job! (assoc opts :now-fn (constantly date))))
        (t/is (= {:daily
                  ["daily/cljdoc-db-2025-12-26_2025-12-26T00-11-12.tar.zst"
                   "daily/cljdoc-db-2025-12-27_2025-12-27T00-11-12.tar.zst"
                   "daily/cljdoc-db-2025-12-28_2025-12-28T00-11-12.tar.zst"
                   "daily/cljdoc-db-2025-12-29_2025-12-29T00-11-12.tar.zst"
                   "daily/cljdoc-db-2025-12-30_2025-12-30T00-11-12.tar.zst"
                   "daily/cljdoc-db-2025-12-31_2025-12-31T00-11-12.tar.zst"
                   "daily/cljdoc-db-2026-01-01_2026-01-01T00-11-12.tar.zst"]
                  :weekly
                  ["weekly/cljdoc-db-2025-12-08_2025-12-08T00-11-12.tar.zst"
                   "weekly/cljdoc-db-2025-12-15_2025-12-15T00-11-12.tar.zst"
                   "weekly/cljdoc-db-2025-12-22_2025-12-22T00-11-12.tar.zst"
                   "weekly/cljdoc-db-2025-12-29_2025-12-29T00-11-12.tar.zst"]
                  :monthly
                  ["monthly/cljdoc-db-2025-02-01_2025-02-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-03-01_2025-03-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-04-01_2025-04-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-05-01_2025-05-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-06-01_2025-06-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-07-01_2025-07-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-08-01_2025-08-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-09-01_2025-09-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-10-01_2025-10-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-11-01_2025-11-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2025-12-01_2025-12-01T00-11-12.tar.zst"
                   "monthly/cljdoc-db-2026-01-01_2026-01-01T00-11-12.tar.zst"]
                  :yearly
                  ["yearly/cljdoc-db-2025-01-01_2025-01-01T00-11-12.tar.zst"
                   "yearly/cljdoc-db-2026-01-01_2026-01-01T00-11-12.tar.zst"]}
                 (list-backups fake-store)))))))

(t/deftest db-restore-test
  (let [fake-store (atom {})
        opts {:object-store-fn (fn [_opts] (FakeObjectStore. fake-store))
              :db-spec {:dbtype "sqlite"
                        :host :none
                        :dbname "target/db-restore-test/cljdoc.db.sqlite"
                        :synchronous "NORMAL"
                        :journal_mode "WAL"}}]
    (t/testing "when db exists we don't restore"
      (fs/with-temp-dir [db-dir {:prefix "cljdoc-db-restore"}]

        (let [db-file (str (fs/file db-dir "cljdoc.db.sqlite"))
              logged (atom [])]
          (with-redefs [log/log* (fn [_logger level _throwable message]
                                   (swap! logged conj [level message]))]

            (spit db-file "I'm here")
            (db-backup/restore-db! (assoc-in opts [:db-spec :dbname] db-file))
            (t/is (match? [[:info #"Database .*/cljdoc.db.sqlite found, no need to restore"]]
                          @logged))))))
    (t/testing "when db does not exist, we can't restore if no backup available"
      (fs/with-temp-dir [db-dir {:prefix "cljdoc-db-restore"}]
        (let [db-file (str (fs/file db-dir "cljdoc.db.sqlite"))
              logged (atom [])]
          (with-redefs [log/log* (fn [_logger level _throwable message]
                                   (swap! logged conj [level message]))]
            (db-backup/restore-db! (assoc-in opts [:db-spec :dbname] db-file))
            (t/is (match? [[:warn #"Database .*/cljdoc.db.sqlite not found, but no backup available"]]
                          @logged))))))
    (t/testing "when db does not exit, we restore from available backup"
      (fs/with-temp-dir [db-dir {:prefix "cljdoc-db-restore"}]
        (let [backup-file (-> (fs/file "target" "dummy-backup.tar.zst")
                              fs/absolutize
                              str)
              backed-up-db-file "cljdoc.db.sqlite"]
          (spit (fs/file "target" backed-up-db-file) "dummy db")
          (p/shell {:dir "target"} "tar --use-compress-program=zstd -cf" backup-file backed-up-db-file)
          (reset! fake-store {"daily/cljdoc-db-2024-09-13_2024-09-14T00-45-22.tar.zst"
                              {:file "somefile"}

                              ;; this is latest daily, it should be picked
                              "daily/cljdoc-db-2024-09-14_2024-09-14T00-45-22.tar.zst"
                              {:file backup-file}

                              "daily/cljdoc-db-2024-09-12_2024-09-14T00-45-22.tar.zst"
                              {:file "some-other-file"}})
          (let [missing-db-file (str (fs/file db-dir "cljdoc.db.sqlite"))
                logged (atom [])]
            (with-redefs [log/log* (fn [_logger level _throwable message]
                                     (swap! logged conj [level message]))]
              (t/is (not (fs/exists? missing-db-file))
                    "db file is missing")
              (db-backup/restore-db! (assoc-in opts [:db-spec :dbname] missing-db-file))
              (t/is (match? [[:info #"Downloading .* for restore"]
                             [:info #"Decompressing.* for restore"]
                             [:info "Database restore complete"]]
                            @logged))
              (t/is (fs/exists? missing-db-file)
                    "db file is restored"))))))))

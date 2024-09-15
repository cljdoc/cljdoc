(ns cljdoc.server.db-backup-test
  (:require [cljdoc.server.db-backup :as db-backup]
            [clojure.test :as t])
  (:import (java.time LocalDateTime)))

(set! *warn-on-reflection* true)

(defn fake-aws []
  (let [fake-store (atom {})]
    {:store fake-store
     :list-backups #(->> @fake-store
                         keys
                         (mapv :Key)
                         sort
                         (group-by (fn [k] (-> (re-matches #"(.*?)/.*" k)
                                               second
                                               keyword))))
     :invoke
     (fn fake-invoke [client {:keys [op request]}]
       (case op
         :DeleteObject
         (swap! fake-store dissoc (select-keys request [:Bucket :Key]))

         :ListObjectsV2
         {:Contents (->> @fake-store
                         vals
                         (mapv #(select-keys % [:Key])))}

         :PutObject
         (swap! fake-store assoc
                (select-keys request [:Bucket :Key])
                (select-keys request [:Bucket :Key :Body :ACL]))

         :CopyObject
         (let [[_ source-bucket source-key] (re-matches #"(.*?)/(.*)" (:CopySource request))
               source (get @fake-store (select-keys request {:Bucket source-bucket :Key source-key}))]
           (fake-invoke client {:op :PutObject :request
                                (merge source (select-keys request [:Bucket :Key]))}))))}))

(t/deftest backups-test
  (let [{:keys [invoke list-backups]} (fake-aws)
        job-opts {:now-fn #(LocalDateTime/parse "2024-09-13T11:12:13")
                  :aws-invoke-fn invoke
                  :backups-bucket-name "cljdoc-backups"
                  :backups-bucket-key "dummy"
                  :backups-bucket-secret "dummy"
                  :backups-bucker-region "dummy"
                  :db-spec {:dbtype "sqlite"
                            :host :none
                            :dbname "target/cljdoc.db.sqlite"
                            :synchronous "NORMAL"
                            :journal_mode "WAL"}
                  :cache-db-spec {:dbtype "sqlite"
                                  :host :none
                                  :dbname "target/cache.db"}}
        initial-expected-backups {:daily ["daily/cljdoc-db-2024-09-13@2024-09-13T11:12:13.tar.zst"]
                                  :weekly ["weekly/cljdoc-db-2024-09-09@2024-09-13T11:12:13.tar.zst"]
                                  :monthly ["monthly/cljdoc-db-2024-09-01@2024-09-13T11:12:13.tar.zst"]
                                  :yearly ["yearly/cljdoc-db-2024-01-01@2024-09-13T11:12:13.tar.zst"]}]
    (t/testing "On 2024-09-13, no backups exist"
      (db-backup/backup-job! job-opts)
      (t/is (= initial-expected-backups (list-backups))))
    (t/testing "backing up again on same day should be no-op"
      (db-backup/backup-job! (assoc job-opts :now-fn #(LocalDateTime/parse "2024-09-13T23:59:59")))
      (t/is (= initial-expected-backups (list-backups))))
    (t/testing "backup on next day results in new daily only"
      (db-backup/backup-job! (assoc job-opts :now-fn #(LocalDateTime/parse "2024-09-14T00:45:22")))
      (t/is (= (update initial-expected-backups :daily conj
                       "daily/cljdoc-db-2024-09-14@2024-09-14T00:45:22.tar.zst")
               (list-backups))))
    (t/testing "2 more backups brings us to Monday and our next weekly backup"
      (doseq [day [15 16]]
        (db-backup/backup-job! (assoc job-opts :now-fn #(LocalDateTime/parse (format "2024-09-%dT00:45:%d"
                                                                                     day day)))))
      (t/is (= (-> initial-expected-backups
                   (update :daily conj
                           "daily/cljdoc-db-2024-09-14@2024-09-14T00:45:22.tar.zst"
                           "daily/cljdoc-db-2024-09-15@2024-09-15T00:45:15.tar.zst"
                           "daily/cljdoc-db-2024-09-16@2024-09-16T00:45:16.tar.zst")
                   (update :weekly conj
                           "weekly/cljdoc-db-2024-09-16@2024-09-16T00:45:16.tar.zst"))
               (list-backups))))

    (t/testing "After 8 daily backups we prune daily backups to a count of 7"
      (doseq [day [17 18 19 20]]
        (db-backup/backup-job! (assoc job-opts :now-fn #(LocalDateTime/parse (format "2024-09-%dT00:45:%d"
                                                                                     day day)))))
      (t/is (= (-> initial-expected-backups
                   (assoc :daily
                          ["daily/cljdoc-db-2024-09-14@2024-09-14T00:45:22.tar.zst"
                           "daily/cljdoc-db-2024-09-15@2024-09-15T00:45:15.tar.zst"
                           "daily/cljdoc-db-2024-09-16@2024-09-16T00:45:16.tar.zst"
                           "daily/cljdoc-db-2024-09-17@2024-09-17T00:45:17.tar.zst"
                           "daily/cljdoc-db-2024-09-18@2024-09-18T00:45:18.tar.zst"
                           "daily/cljdoc-db-2024-09-19@2024-09-19T00:45:19.tar.zst"
                           "daily/cljdoc-db-2024-09-20@2024-09-20T00:45:20.tar.zst"])
                   (update :weekly conj
                           "weekly/cljdoc-db-2024-09-16@2024-09-16T00:45:16.tar.zst"))
               (list-backups))))

    (t/testing "After daily backups for until 1st day of 2026"
      (let [start-date (LocalDateTime/parse "2024-09-20T00:11:12")
            cutoff-date (LocalDateTime/parse "2026-01-02T00:00:00")]
        (doseq [date (->> (iterate (fn [^LocalDateTime d] (.plusDays d 1)) start-date)
                          (take-while (fn [^LocalDateTime d] (.isBefore d cutoff-date))))]
          (db-backup/backup-job! (assoc job-opts :now-fn (constantly date))))
        (t/is (= {:daily
                  ["daily/cljdoc-db-2025-12-26@2025-12-26T00:11:12.tar.zst"
                   "daily/cljdoc-db-2025-12-27@2025-12-27T00:11:12.tar.zst"
                   "daily/cljdoc-db-2025-12-28@2025-12-28T00:11:12.tar.zst"
                   "daily/cljdoc-db-2025-12-29@2025-12-29T00:11:12.tar.zst"
                   "daily/cljdoc-db-2025-12-30@2025-12-30T00:11:12.tar.zst"
                   "daily/cljdoc-db-2025-12-31@2025-12-31T00:11:12.tar.zst"
                   "daily/cljdoc-db-2026-01-01@2026-01-01T00:11:12.tar.zst"]
                  :weekly
                  ["weekly/cljdoc-db-2025-12-08@2025-12-08T00:11:12.tar.zst"
                   "weekly/cljdoc-db-2025-12-15@2025-12-15T00:11:12.tar.zst"
                   "weekly/cljdoc-db-2025-12-22@2025-12-22T00:11:12.tar.zst"
                   "weekly/cljdoc-db-2025-12-29@2025-12-29T00:11:12.tar.zst"]
                  :monthly
                  ["monthly/cljdoc-db-2025-02-01@2025-02-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-03-01@2025-03-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-04-01@2025-04-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-05-01@2025-05-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-06-01@2025-06-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-07-01@2025-07-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-08-01@2025-08-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-09-01@2025-09-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-10-01@2025-10-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-11-01@2025-11-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2025-12-01@2025-12-01T00:11:12.tar.zst"
                   "monthly/cljdoc-db-2026-01-01@2026-01-01T00:11:12.tar.zst"]
                  :yearly
                  ["yearly/cljdoc-db-2025-01-01@2025-01-01T00:11:12.tar.zst"
                   "yearly/cljdoc-db-2026-01-01@2026-01-01T00:11:12.tar.zst"]}
                 (list-backups)))))))
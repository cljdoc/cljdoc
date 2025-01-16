(ns cljdoc.server.db-backup
  "Periodically backup SQLite database to S3 compatible storage.

  See `backup-retention` for how many backups we keep for each backup period.

  Naming scheme is: `<backup period>/<prefix><target-date>_<timestamp><ext>` where:
  - `<backup period>` are `daily`, `weekly`, `monthly`, `yearly`
  - `<prefix>` currently `cljdoc-db-`
  - `<target-date>` is `yyyy-MM-dd` for logical backup date
  - `<timestamp>` is `yyyy-MM-ddTHH-mm-ss` for actual date of backup
  - `<ext>` currently `.tar.zst`

  We chose zstd as our compression format. In testing performed much better than gz in
  compression speed, decompression speed and size.

  Missing `weekly`, `montly`, and `yearly` backups are always copied from available
  `daily` backups on a best fit basis. For example if there is no `yearly` backup for 2024
  we'll fill it with the best candidate from our `daily` backups. This is why you might see
  something like:

  `yearly/cljdoc-db-2024-01-01_2024-09-15T13:14:52.tar.zst`.

  In this case, the available daily backup from Sept 15th 2024 was our best fit."
  (:require [babashka.fs :as fs]
            [babashka.process :as process]
            [cljdoc.s3 :as s3]
            [cljdoc.server.log-init] ;; to quiet odd jetty DEBUG logging
            [cljdoc.util.sentry :as sentry]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [next.jdbc :as jdbc]
            [tea-time.core :as tt])
  (:import (java.lang AutoCloseable)
           (java.time DayOfWeek LocalDate LocalDateTime Period)
           (java.time.format DateTimeFormatter)
           (java.time.temporal TemporalAdjusters)
           (java.util.concurrent TimeUnit)
           (org.sqlite SQLiteConnection)
           (org.sqlite.core DB$ProgressObserver)))

(set! *warn-on-reflection* true)

(def ^:private backup-retention {:daily 7
                                 :weekly 4
                                 :monthly 12
                                 :yearly 2})

(defn- s3-list->backups [backups]
  (->> backups
       (mapv :key)
       ;; TODO: consider warning on non-matches
       (keep #(re-matches #"(?x)                               # group 0: key
                              (daily|weekly|monthly|yearly)    # group 1: period
                              /
                              (.*)                             # group 2: prefix
                              (\d{4}-\d{2}-\d{2})              # group 3: target-date
                              _
                              (\d{4}-\d\d-\d\dT\d\d-\d\d-\d\d) # group 4: timestamp
                              (.*)                             # group 5: extension"
                          %))
       (mapv #(zipmap [:key :period :prefix :target-date :timestamp :extension] %))
       (mapv #(update % :period keyword))
       (mapv #(update % :target-date (fn [s] (LocalDate/parse s))))))

(defn- existing-backups [object-store]
  (-> (s3/list-objects object-store)
      s3-list->backups))

(defn- get-backup-for [backup-list period ^LocalDate target-date]
  (some #(and (= period (:period %))
              (= target-date (:target-date %)))
        backup-list))

(defn- db-backup-filename [^LocalDateTime datetime]
  (format "cljdoc-db-%s_%s.tar.zst"
          (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd") datetime)
          (.format (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH-mm-ss") datetime)))

(defn create-backup-tracker []
  (let [last-report-time (atom (System/currentTimeMillis))]
    (fn track-backup-status [{:keys [dbname total-pages remaining-pages]}]
      (let [time-now (System/currentTimeMillis)]
        (cond
          (zero? total-pages)
          (log/warnf "%s empty" dbname)

          (>= time-now (+ @last-report-time 1000))
          (do
            (reset! last-report-time time-now)
            (log/infof "%s backup %.2f%% complete" dbname (* 100 (/ (- total-pages remaining-pages) (float total-pages))))))))))

(defn- backup-sqlite-db! [{:keys [dbname] :as db-spec} dest-dir]
  ;; TODO: consider warning on missing dbs?
  (let [target (str (fs/file dest-dir (fs/file-name dbname)))]
    (log/infof "Backing up %s db to %s" dbname target)
    (with-open  [^SQLiteConnection conn (jdbc/get-connection db-spec)]
      (let [backup-tracker-fn (create-backup-tracker)
            backup-result
            ;; TODO: check return, should be 0 for OK
            ;; https://www.sqlite.org/c3ref/c_abort.html
            (.backup (.getDatabase conn) "main" target
                     (reify DB$ProgressObserver
                       (progress [_ remaining-pages total-pages]
                         (backup-tracker-fn {:dbname dbname
                                             :total-pages total-pages
                                             :remaining-pages remaining-pages}))))]
        (log/infof "%s backup complete, result-code %d" dbname backup-result)))))

(defn- backup-db!
  "Create compressed backup for `db-spec` and `cache-db-spec` to `dest-file`"
  [dest-file {:keys [db-spec cache-db-spec] :as _opts}]
  (fs/with-temp-dir [backup-work-dir {:prefix "cljdoc-db-backup-work"}]
    (backup-sqlite-db! db-spec backup-work-dir)
    (backup-sqlite-db! cache-db-spec backup-work-dir)
    (let [db-files (->> (fs/list-dir backup-work-dir)
                        (mapv fs/file-name))]
      (log/infof "Compressing backup to %s" dest-file)
      ;; specifying db-files instead of simply . avoids including . in tar
      (apply process/shell {:dir backup-work-dir} "tar --use-compress-program=zstd -cf" dest-file db-files))))

(comment
  (def db-spec {:dbtype "sqlite"
                :host :none
                :dbname "data/cljdoc.db.sqlite"})

  (backup-sqlite-db! db-spec "target")

  (jdbc/execute!
   db-spec
   ["create table sample(id, name)"])

  (jdbc/execute! db-spec ["backup backup.db"])

  (with-open [conn (jdbc/get-connection db-spec)]
    (try
      (let [stmt (.createStatement conn)]
        (.execute stmt "backup to 'backup.db'")
        (println "Backup completed successfully."))
      (catch Exception e
        (println "Backup failed:" (.getMessage e)))))

  (with-open [conn (jdbc/get-connection db-spec)]
    (let [conn (cast SQLiteConnection conn)]
      (.backup conn "target/foo.db" nil nil))))

(defn- store-daily-backup!
  [object-store backup-file]
  (let [target-key (str "daily/" (fs/file-name backup-file))]
    (log/infof "Storing %s" target-key)
    (s3/put-object object-store target-key backup-file)
    (log/infof "Storing complete for %s" target-key)))

(defn- ideal-backups [^LocalDateTime datetime]
  (let [date (.toLocalDate datetime)
        backup-calcs {:daily   {:start-fn identity
                                :period (Period/ofDays 1)}
                      :weekly  {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/previousOrSame DayOfWeek/MONDAY)))
                                :period (Period/ofWeeks 1)}
                      :monthly {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/firstDayOfMonth)))
                                :period (Period/ofMonths 1)}
                      :yearly  {:start-fn (fn [^LocalDate date]
                                            (.with date (TemporalAdjusters/firstDayOfYear)))
                                :period (Period/ofYears 1)}}]
    (->> (for [period-key [:daily :weekly :monthly :yearly]
               :let [{:keys [start-fn ^Period period]} (period-key backup-calcs)
                     count (period-key backup-retention)]]
           (->> (iterate (fn [^LocalDate d] (.minus d period)) (start-fn date))
                (take count)
                (mapv (fn [^LocalDate d] {:period period-key :target-date d :max-date (.plus d period)}))))
         (mapcat identity))))

(defn- fillable-backups [existing-backups ideal-backups]
  (let [missing-backups (let [existing-keys (->> existing-backups
                                                 (mapv #(select-keys % [:period :target-date]))
                                                 set)]
                          (remove #(contains? existing-keys (select-keys % [:period :target-date])) ideal-backups))
        daily-backups (->> existing-backups
                           (filterv #(= :daily (:period %)))
                           (sort-by :target-date))]
    (reduce (fn [acc {:keys [^LocalDate target-date max-date] :as missing}]
              (if-let [daily-match (some (fn [daily]
                                           (let [^LocalDate daily-target-date (:target-date daily)]
                                             (when (and (or (.isEqual target-date daily-target-date)
                                                            (.isAfter daily-target-date target-date))
                                                        (.isBefore daily-target-date max-date))
                                               daily)))
                                         daily-backups)]
                (conj acc (assoc missing :daily-match daily-match))
                acc))
            []
            missing-backups)))

(defn- fill-backup [object-store source dest]
  (log/infof "Filling %s from %s" dest source)
  (s3/copy-object object-store source dest))

(defn- fill-copy-list [fillable-backups]
  (into [] (for [{:keys [period target-date daily-match]} fillable-backups
                 :let [source (:key daily-match)
                       dest (format "%s/%s%s_%s%s"
                                    (name period)
                                    (:prefix daily-match)
                                    target-date
                                    (:timestamp daily-match)
                                    (:extension daily-match))]]
             [source dest])))

(defn- prunable-backups [existing-backups]
  (->> existing-backups
       (sort-by :timestamp)
       reverse
       (group-by :period)
       (mapcat (fn [[period backups]]
                 (let [keep-count (period backup-retention)]
                   (drop keep-count backups))))
       (into [])))

(defn- prune-backup! [object-store {:keys [key]}]
  (log/infof "Pruning %s" key)
  (s3/delete-object object-store key))

(defn- daily-backup! [object-store ^LocalDateTime datetime opts]
  (let [existing (existing-backups object-store)]
    (when-not (get-backup-for existing :daily (.toLocalDate datetime))
      (fs/with-temp-dir [backup-file-dir {:prefix "cljdoc-db-backup"}]
        (let [backup-file (str (fs/file backup-file-dir (db-backup-filename datetime)))]
          (backup-db! backup-file opts)
          (store-daily-backup! object-store backup-file))))))

(defn- fill-missing-backups! [object-store ^LocalDateTime datetime]
  (let [existing (existing-backups object-store)
        ideal (ideal-backups datetime)
        fillable (fillable-backups existing ideal)]
    (doseq [[source dest] (fill-copy-list fillable)]
      (fill-backup object-store source dest))))

(defn- prune-old-backups! [object-store]
  (let [existing (existing-backups object-store)]
    (doseq [backup (prunable-backups existing)]
      (prune-backup! object-store backup))))

(defn backup-job! [{:keys [now-fn object-store-fn] :as opts}]
  (log/info "Backup job started")
  (let [now (now-fn)]
    (with-open [^AutoCloseable object-store (object-store-fn opts)]
      (daily-backup! object-store now opts)
      (fill-missing-backups! object-store now)
      (prune-old-backups! object-store)))
  (log/info "Backup job complete"))

(defn- wrap-error [wrapped-fn]
  (fn []
    (try
      (wrapped-fn)
      (catch Exception e
        (log/error e)
        (sentry/capture {:ex e})))))

(defn restore-db!
  "If `db-spec` `dbname` does not exist, attempt automatic restore from latest available daily backup.

  There is also `cache-db-spec`, but we solely use `db-spec` to determine if we should restore both dbs.
  Call before any db interaction, else empty db will automatically be created."
  [{:keys [object-store-fn db-spec] :as opts}]
  (let [object-store-fn (or object-store-fn s3/make-exo-object-store)]
    (with-open [^AutoCloseable object-store (object-store-fn opts)]
      (let [dbname (:dbname db-spec)
            existing-backup-key (->> (existing-backups object-store)
                                     (filterv #(= :daily (:period %)))
                                     (sort-by :target-date)
                                     last
                                     :key)]
        (if (fs/exists? dbname)
          (log/infof "Database %s found, no need to restore" dbname)
          (if-not existing-backup-key
            (log/warnf "Database %s not found, but no backup available" dbname)
            (fs/with-temp-dir [download-dir {:prefix "cljdoc-db-restore-work"}]
              (let [fname (fs/file-name existing-backup-key)
                    dest-file (fs/file download-dir fname)
                    target-dir (str (fs/parent dbname))]
                (log/infof "Downloading %s to %s for restore" fname download-dir)
                (s3/get-object object-store existing-backup-key dest-file)
                (log/infof "Decompressing %s to %s for restore" fname target-dir)
                (process/shell {:dir target-dir} "tar --use-compress-program=zstd -xf" dest-file)
                (log/info "Database restore complete")))))))))

(defmethod ig/init-key :cljdoc/db-backup
  [k {:keys [enable-db-backup?] :as opts}]
  (if-not enable-db-backup?
    (log/info "Database backup disable, skipping " k)
    (do (log/info "Starting" k)
        {::db-backup-job (tt/every!
                           ;; we backup daily but check more often to cover failure cases
                          (.toSeconds TimeUnit/HOURS 2)
                           ;; wait 30 minutes to avoid overlap with blue/green deploy and any db restore work
                          (.toSeconds TimeUnit/MINUTES 30)
                          (wrap-error #(backup-job! (assoc opts
                                                           :object-store-fn s3/make-exo-object-store
                                                           :now-fn (fn [] (LocalDateTime/now))))))})))

(defmethod ig/halt-key! :cljdoc/db-backup
  [k db-backup]
  (when db-backup
    (log/info "Stopping" k)
    (tt/cancel! (::db-backup-job db-backup))))

(comment
  (require '[cljdoc.config :as cfg])

  (def opts (assoc (cfg/db-backup (cfg/config)) :object-store-fn s3/make-exo-object-store))

  (db-backup-filename (LocalDateTime/now))
  ;; => "cljdoc-db-2024-09-22_2024-09-22T15-38-27.tar.zst"

  :eoc)

(ns cljdoc.server.clojars-stats
  "Maintain a database table with Clojars stats.

  Includes background jobs to download stats and prune stats that are
  no longer needed. Need is defined via a configurable retention timeframe
  specified in days."
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.core.memoize :as memoize]
            [cljdoc.util.sentry :as sentry]
            [integrant.core :as ig]
            [tea-time.core :as tt])
  (:import (java.time LocalDate)
           (java.time.format DateTimeFormatter)
           (java.util.concurrent TimeUnit)))

(defn- stats-files
  "Clojars download stats are hosted on s3 and generated once per day
  to https://clojars.org/stats/downloads-YYYYMMDD.edn.
  Clojars docs say to allow for gaps (missing days).

  A list of available clojars stats files is available at https://clojars.org/stats.
  If no query string is provided to clojars, it has s3
  render a list of files nicey nicey via JavaScript for human consumption in the browser.

  With a query string we get XML back, which is what we want.
  https://docs.aws.amazon.com/AmazonS3/latest/API/API_ListObjectsV2.html
  Note that a maxium of 1000 entries will be returned by s3 per fetch and we currently make no
  attempt to do multiple fetches to overcome this limit."
  [retention-date]
  (let [start-after-key (format "downloads-%s.edn" (.format DateTimeFormatter/BASIC_ISO_DATE (.minusDays retention-date 1)))]
    ;; notes:
    ;; - in v2 of the aws API, marker is renamed to start-after, clojars seems to be using v1 at this time
    ;; - it does not seem to matter if the marker does not exist, we'll still get all entries (oldest to newest)
    ;;   after the entry had it existed
    (->> (slurp (str "https://clojars.org/stats/?prefix=downloads-&marker=" start-after-key))
         (re-seq #"(?:<Key>)(downloads-\d{8}\.edn)(?:</Key>)")
         (map second)
         (map #(str "https://clojars.org/stats/" %)))))

(defn- uri->date [uri]
  (-> (last (re-find #"(?:downloads-)(\d{8})" uri))
      (LocalDate/parse DateTimeFormatter/BASIC_ISO_DATE)))

(defn- to-import [db-spec retention-date]
  (let [existing (set (map :date (sql/query db-spec ["select distinct date from clojars_stats"])))]
    (->> (stats-files retention-date)
         (map (fn [f] {:file f :date (uri->date f)}))
         (remove #(.isBefore (:date %) retention-date))
         (remove #(contains? existing (str (:date %))))
         (map :file)
         sort)))

(defn- parse-file
  ;; sample:
  #_{["viz-cljc" "viz-cljc"] {"0.1.2" 2, "0.1.3" 39}, ["io.nervous" "cljs-lambda"] {"0.3.5" 2}}
  [uri]
  (let [date (uri->date uri)]
    (assert date)
    (map (fn [[[group artifact] downloads]]
           {:date (do (assert date) date)
            :group_id (do (assert group) group)
            :artifact_id (do (assert artifact) artifact)
            :downloads (do (assert downloads) (reduce + (vals downloads)))})
         (edn/read-string (slurp uri)))))

(defn clean!
  [{:keys [db-spec retention-date]}]
  (log/info "pruning any old stats before" (str retention-date))
  (sql/execute! db-spec ["DELETE FROM clojars_stats WHERE date(date) < ?" retention-date]))

(defn update!
  "Download and store download statistics from clojars, returns count of daily stats successfully processed."
  [{:keys [db-spec retention-date]}]
  (let [todo (to-import db-spec retention-date)]
    (when (pos? (count todo))
      (log/info "clojars download stat files to import:" (count todo)))
    (reduce (fn [processed-cnt f]
              (Thread/sleep 250) ;; give other activities a chance too (was that the idea?)
              (try
                (let [parsed-stats (parse-file f)]
                  (log/infof "%s has %s artifacts" f (count parsed-stats))
                  (sql/insert-multi! db-spec "clojars_stats" parsed-stats))
                (inc processed-cnt)
                (catch Exception e
                  (log/errorf e "Failed to process %s" f)
                  processed-cnt)))
            0
            todo)))

(defn- wrap-error [wrapped-fn]
  (fn []
    (try
      (wrapped-fn)
      (catch Exception e
        (log/error e)
        (sentry/capture {:ex e})))))

(defprotocol IClojarsStats
  (download-count-max [_] "Return download count for artifact with most downloads")
  (download-count-artifact [_ group-id artifact-id] "Return download count for artifact uniquely described by `group-id` `artifact-id`"))

(defn ^{:clojure.core.memoize/args-fn rest} download-counts [db-spec]
  (let [cnts (->> (sql/query db-spec [(str "select group_id, artifact_id, sum(downloads) as downloads "
                                           "from clojars_stats "
                                           "group by group_id, artifact_id")])
                  (reduce (fn [acc {:keys [group_id artifact_id downloads]}]
                            (assoc acc [group_id artifact_id] downloads))
                          {}))]
    (assoc cnts ::max (if (seq cnts)
                        (reduce max (vals cnts))
                        0))))

(def memoized-download-counts (memoize/memo #'download-counts))

(defn clear-cache! []
  (memoize/memo-clear! memoized-download-counts))

(defrecord ClojarsStats [db-spec]
  IClojarsStats
  (download-count-max [_]
    (-> (memoized-download-counts db-spec)
        ::max))
  (download-count-artifact [_ group-id artifact-id]
    ;; we don't have entire clojars history, assume 0 downloads for an artifact we don't fid
    (-> (memoized-download-counts db-spec)
        (get [group-id artifact-id] 0))))

(defn update-stats! [{:keys [retention-days] :as opts}]
  (let [opts (assoc opts :retention-date (.minusDays (LocalDate/now) retention-days))]
    (when (> (update! opts) 0)
      (clean! opts)
      (clear-cache!))))

(defmethod ig/init-key :cljdoc/clojars-stats
  [k {:keys [db-spec retention-days] :as opts}]
  (when (> retention-days 1000)
    (throw (ex-info (format "retention-days is %d, but max for cljdoc's clojars stats is currently 1000" retention-days) {})))
  (log/info "Starting" k)
  (-> (->ClojarsStats db-spec)
      (assoc ::poll-job (tt/every! (.toSeconds TimeUnit/HOURS 1) (wrap-error #(update-stats! opts))))))

(defmethod ig/halt-key! :cljdoc/clojars-stats
  [k clojars-stats]
  (log/info "Stopping" k)
  (tt/cancel! (::poll-job clojars-stats)))

(comment
  (stats-files (.minusDays (LocalDate/now) 10))

  (require '[cljdoc.config :as cfg])
  (def db-spec (-> (cfg/config) (cfg/db)))

  (::max (memoized-download-counts db-spec))

  (clear-cache!)

  (get (memoized-download-counts db-spec) ["rewrite-clj" "rewrite-clj"])

  (download-count-artifact (->ClojarsStats db-spec)  "rewrite-clj" "rewrite-clj")
  (download-count-max (->ClojarsStats db-spec))

  (to-import db-spec (.minusDays (LocalDate/now) 22))

  (uri->date "https://clojars.org/stats/downloads-20220326.edn")

  (parse-file "https://clojars.org/stats/downloads-20220326.edn")

  (clean! {:db-spec db-spec :retention-date (LocalDate/now)})

  (update-stats! {:db-spec db-spec :retention-days 4})

  nil)

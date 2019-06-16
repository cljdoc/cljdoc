(ns cljdoc.server.clojars-stats
  "Maintain a database table with Clojars stats.

  Includes background jobs to download stats and prune stats that are
  no longer needed. Need is defined via a retention timeframe specified
  in days."
  (:require [clojure.java.jdbc :as sql]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [cljdoc.util.sentry :as sentry]
            [integrant.core :as ig]
            [tea-time.core :as tt])
  (:import (java.time LocalDate)))

(defn- stats-files []
  (->> (slurp "https://clojars.org/stats/")
       (re-seq #"downloads-\d{8}\.edn")
       (map #(str "https://clojars.org/stats/" %))
       (set)))

(defn- uri->date [uri]
  (->> (rest (re-find #"(\d{4})(\d{2})(\d{2})" uri))
       (string/join "-")
       (LocalDate/parse)))

(defn- to-import [db-spec retention-days]
  (let [existing (set (map :date (sql/query db-spec ["select distinct date from clojars_stats"])))
        retention-date (.minusDays (LocalDate/now) retention-days)]
    (->> (stats-files)
         (map (fn [f] {:file f :date (uri->date f)}))
         (remove #(.isBefore (:date %) retention-date))
         (remove #(contains? existing (str (:date %))))
         (map :file)
         sort)))

(defn- parse-file [uri]
  (let [date (uri->date uri)]
    (assert date)
    (mapcat (fn [[[group artifact] downloads]]
              (for [d downloads]
                {:date (do (assert date) date)
                 :group_id (do (assert group) group)
                 :artifact_id (do (assert artifact) artifact)
                 :version (do (assert (key d)) (key d))
                 :downloads (do (assert (val d)) (val d))}))
            (edn/read-string (slurp uri)))))

(defn update!
  "Download and store download statistics from clojars."
  [{:keys [db-spec retention-days]}]
  (let [todo (to-import db-spec retention-days)]
    (when (pos? (count todo))
      (log/info (count todo) "files to import..."))
    (doseq [f (take 30 todo)]
      (Thread/sleep 1000)
      (let [parsed-stats (parse-file f)]
        (log/infof "Read %s... %s releases\n" f (count parsed-stats))
        (sql/insert-multi! db-spec "clojars_stats" parsed-stats)))
    (< 0 (count todo))))

(defn clean!
  [{:keys [db-spec retention-days]}]
  (sql/execute! db-spec ["DELETE FROM clojars_stats WHERE date(date) < ?"
                         (.minusDays (LocalDate/now) retention-days)]))

(defn- wrap-error [wrapped-fn]
  (fn []
    (try
      (wrapped-fn)
      (catch Exception e
        (log/error e)
        (sentry/capture {:ex e})))))

(defprotocol IClojarsStats
  (artifact-monthly [_ group-id artifact-id])
  (downloads [_]))

(defrecord ClojarsStats [db-spec]
  IClojarsStats
  (artifact-monthly [_ group-id artifact-id]
    (sql/query db-spec ["SELECT strftime('%Y-%m', date) as month, SUM(downloads) as downloads FROM clojars_stats WHERE group_id = ? AND artifact_id = ? GROUP BY month" group-id artifact-id]))
  (downloads [_]
    (sql/query db-spec ["SELECT SUM(downloads) as downloads, group_id as 'group-id', artifact_id as 'artifact-id' FROM clojars_stats GROUP BY group_id, artifact_id"])))

(defmethod ig/init-key :cljdoc/clojars-stats
  [k {:keys [db-spec retention-days] :as opts}]
  (log/info "Starting" k)
  (-> (->ClojarsStats db-spec)
      (assoc ::poll-job (tt/every! (* 1 60) (wrap-error #(update! opts)))
             ::clean-job (tt/every! (* 60 60) (wrap-error #(clean! opts))))))

(defmethod ig/halt-key! :cljdoc/clojars-stats
  [k clojars-stats]
  (log/info "Stopping" k)
  (tt/cancel! (::poll-job clojars-stats))
  (tt/cancel! (::clean-job clojars-stats)))

(comment)



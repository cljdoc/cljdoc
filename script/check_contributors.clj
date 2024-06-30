#!/usr/bin/env bb

(ns check-contributors
  (:require
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.set :as cset]
   [clojure.string :as string]
   [helper.main :as main]
   [lread.status-line :as status]))

(defn next-url [response]
  (some-> response
          :headers
          (get "link")
          (->> (re-find #"(?:<)(\S+)(?:>; rel=\"next\")"))
          last))

(defn fetch-all-responses [url opts]
  (loop [url url
         responses []]
    (println "url" url)
    (let [r (http/get url opts)
          url (next-url r)]
      (if-not url
        (conj responses r)
        (recur url (conj responses r))))))

(defn fetch-all [api-request]
  (let [token (System/getenv "GITHUB_TOKEN")
        opts (if token
               {:headers {"Authorization" (format "Bearer %s" token)}}
               {})]
    (->> (fetch-all-responses (str "https://api.github.com" api-request)
                              opts)
         (map :body)
         (map #(json/parse-string % true))
         (mapcat identity))))

(defn fetch-issue-creators-for-repo [repo]
  (->> (fetch-all (format "/repos/%s/issues?state=all&per_page=100" repo))
       (remove :pull_request)
       (map #(-> % :user :login))
       distinct
       (map #(vector repo %))))

(defn fetch-commiters-for-repo [repo]
  (->> (fetch-all (format "/repos/%s/contributors?per_page=100" repo))
       (map :login)
       (remove #(string/ends-with? % "[bot]"))
       distinct
       (map #(vector repo %))))

(defn by-id [vrepo-id]
  (->> vrepo-id
       (reduce (fn [acc [repo id]]
                 (update acc id (fnil conj #{}) repo))
               {})
       (reduce-kv (fn [m k v]
                    (assoc m k (-> v sort vec)))
                  {})))

(defn fetch-commiters [repos]
  (status/line :detail "Getting commiters")
  (->> repos
       (mapcat fetch-commiters-for-repo)
       by-id))

(defn fetch-issue-creators [repos]
  (status/line :detail "Getting issue creators")
  (->> repos
       (mapcat fetch-issue-creators-for-repo)
       by-id))

(defn difference [a b]
  (-> (cset/difference (set a) (set b))
      vec
      sort))

(defn- report-uncredited [actual-contributors credited-contributor-ids]
  (let [ids (difference (keys actual-contributors) credited-contributor-ids)]
    (if (seq ids)
      (doseq [id ids]
        (status/line :detail " %s: %s" id (get actual-contributors id)))
      (status/line :detail "<none>"))))

(defn- report-credit-not-on-github [actual-contributors credited-contributor-ids]
  (let [ids (difference credited-contributor-ids (keys actual-contributors))]
    (if (seq ids)
      (doseq [id ids]
        (status/line :detail " %s" id))
      (status/line :detail "<none>"))))

(defn reconcile []
  (status/line :head "Fetching actual contributors from GitHub")
  (let [repos ["cljdoc/cljdoc" "cljdoc/cljdoc-analyzer" "cljdoc/cljdoc-check-action"]
        actual-issue-creators (fetch-issue-creators repos)
        actual-commiters (fetch-commiters repos)
        our-records "doc/people.edn"]
    (status/line :head "Reconciling with our records in %s" our-records)
    (let [people (->> our-records slurp edn/read-string :contributors
                      (remove :exclude-from-reconcile))
          credited-commiter-ids (->> people
                                     (keep #(when (some #{:code :doc} (:contributions %))
                                              (:github-id %))))
          credited-issue-creator-ids (->> people
                                          (keep #(when (some #{:issue} (:contributions %))
                                                   (:github-id %))))]

      (status/line :head "Uncredited issue creators")
      (report-uncredited actual-issue-creators credited-issue-creator-ids)

      (status/line :head "Uncredited commiters")
      (report-uncredited actual-commiters credited-commiter-ids)

      (status/line :head "Credited issue creators not found on GitHub")
      (report-credit-not-on-github actual-issue-creators credited-issue-creator-ids)

      (status/line :head "Credited commiters not found on GitHub")
      (report-credit-not-on-github actual-commiters credited-commiter-ids)

      (status/line :detail "\nMake any necessary updates to %s" our-records))))

(defn -main [& _args]
  (reconcile))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

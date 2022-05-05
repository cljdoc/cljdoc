#!/usr/bin/env bb

(ns outdated
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as string]
            [doric.core :as doric]
            [helper.main :as main]
            [helper.shell :as shell]
            [lread.status-line :as status]))

(defn check-clojure []
  (status/line :head "Checking Clojure deps")
  ;; antq will return 1 when there are outdated deps, so don't fail on non-zero exit
  (shell/command {:continue true} "clojure -M:outdated"))

(defn check-npm-js []
  (when (not (fs/exists? "node_modules"))
    (status/line :head "Checking npm JavaScript deps: installing node_modules")
    (shell/command "npm ci"))

  ;; don't understand exit code convention for npm outdated, but do know it
  ;; spits nothing to stdout, we don't have anything to update
  (status/line :head "Checking npm JavaScript deps")
  (let [out (-> (shell/command {:out :string :err :string :continue true}
                               "npm outdated")
                :out)]
    (print out)
    (flush)
    (when (not (seq out))
      (status/line :detail "All npm JavaScript deps seem up to date."))))

(defn check-cdn-js []
  (status/line :head "Checking CDN JavaScript deps")
  (let [source "resources/asset-deps.edn"
        outdated (->> source
                      slurp
                      edn/read-string
                      vals
                      (keep (fn [{:keys [npm-name version note] :as asset}]
                              (let [latest-version (-> (shell/command {:out :string :err :string}
                                                                      "npm show" npm-name "dist-tags.latest")
                                                       :out
                                                       string/trim)]
                                (when (not= version latest-version)
                                  (assoc asset
                                         :file source
                                         :current version
                                         :latest latest-version))))))]
    (if (seq outdated)
      (->> (for [o outdated
                 :let [note-lines (when (:note o)
                                    (string/split-lines (:note o)))]]
             (concat [(assoc o :note (first note-lines))]
                     (map (fn [l] {:note l}) (rest note-lines))))
           (mapcat identity)
           (doric/table [:file :npm-name :current :latest :note])
           println)
      (status/line :detail "All CDN JavaScript deps seem up to date."))))

(defn -main []
  (check-clojure)
  (check-npm-js)
  (check-cdn-js))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

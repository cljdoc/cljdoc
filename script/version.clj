#!/usr/bin/env bb

(ns version
  (:require [babashka.tasks :as t]
            [clojure.string :as str]
            [helper.main :as main]))

(defn version []
  (let [base-version "0.0"
        commit-count (-> (t/shell {:out :string} "git rev-list --count HEAD") :out str/trim)
        commit-sha (-> (t/shell {:out :string} "git rev-parse --short HEAD") :out str/trim)
        branch (-> (t/shell {:out :string} "git rev-parse --abbrev-ref HEAD") :out str/trim)]
    (if (= "master" branch)
      (format "%s.%s-%s" base-version commit-count commit-sha)
      (format "%s.%s-%s-%s" base-version commit-count branch commit-sha))))

(defn -main [& _args]
  (println (version)))

(main/when-invoked-as-script
 (apply -main *command-line-args*))

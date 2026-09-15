(ns deps-js
  (:require [babashka.fs :as fs]
            [helper.shell :as shell]
            [lread.status-line :as status]))

(defn task [_opts]
  (status/line :head "Downloading JS deps")
  (cond
    (not (fs/exists? "package-lock.json"))
    (do
      (status/line :detail "package-lock.json missing, running: npm install")
      (shell/command "npm install"))

    (seq (fs/modified-since "package-lock.json" "package.json"))
    (do
      (status/line :detail "package.json modified after package-lock.json, running: npm install")
      (shell/command "npm install"))

    (not (fs/exists? "node_modules"))
    (do
      (status/line :detail "node_modules missing, running: npm ci")
      (shell/command "npm ci"))

    (seq (fs/modified-since "node_modules" "package-lock.json"))
    (do
      (status/line :detail "package-lock.json modified after node_modules, running: npm ci")
      (shell/command "npm ci"))

    :else
    (status/line :detail "skipping: node_modules, package.json & package-lock.json all in order")))

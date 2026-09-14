(ns nvd-scan
  (:require [helper.shell :as shell]))

(defn task [_opts]
  (shell/command {:dir "./modules/nvd-scan"} "bb nvd-scan"))

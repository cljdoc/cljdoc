(ns nvd-scan
  (:require [helper.shell :as shell]
            [lread.status-line :as status]))

(defn task [_opts]
  (status/line :head "Running vulneralibity scan")
  (shell/command {:dir "./modules/nvd-scan"} "bb nvd-scan"))

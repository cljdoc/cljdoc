(ns clean
  (:require [babashka.fs :as fs]
            [lread.status-line :as status]))

(defn task [_opts]
  (status/line :head "Cleaning build work")
  (status/line :detail "Deleting (d=deleted -=did not exist)")
  (run! (fn [d]
          (status/line :detail "[%s] %s"
                       (if (fs/exists? d) "d" "-")
                       d)
          (fs/delete-tree d))
        ["target"
         "resources-compiled"
         ".cpcache"
         "modules/deploy/.cpcache"
         "test-data/server"
         "test-data/cli"]))

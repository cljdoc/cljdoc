;; a wee special purpose script to grab our configured cljdoc data directory
(require '[clojure.java.io :as io]
         '[cljdoc.config :as config])

(println (-> (config/config)
             (config/data-dir)
             io/file
             .getCanonicalPath
             str))

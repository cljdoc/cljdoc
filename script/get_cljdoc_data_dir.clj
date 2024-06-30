;; a wee special purpose script to grab our configured cljdoc data directory
(require '[cljdoc.config :as config]
         '[clojure.java.io :as io])

(println (-> (config/config)
             (config/data-dir)
             io/file
             .getCanonicalPath
             str))

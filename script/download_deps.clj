(ns download-deps
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.tools.build.api :as b]))

(defn task [_opts]
  (doseq [deps-edn ["deps.edn" "ops/exoscale/deploy/deps.edn"]]
    (let [aliases (->> deps-edn
                       slurp
                       edn/read-string
                       :aliases
                       keys)
          deps-dir (str (fs/parent deps-edn))]
      (println "-" deps-edn)
      ;; one at a time because aliases with :replace-deps will... well... you know.
      (println "Bring down default deps")
      (b/create-basis {:dir deps-dir})
      (doseq [a (sort aliases)]
        (println "Bring down deps for alias" a)
        (b/create-basis {:dir deps-dir :aliases [a]})))))

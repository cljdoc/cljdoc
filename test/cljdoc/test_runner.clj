(ns cljdoc.test-runner
  (:require [eftest.runner :as eft :refer [find-tests run-tests]]))

(defn -main []
  (eft/run-tests (eft/find-tests "test")
                 {:capture-output? true
                  :report-to-file "target/junit.xml"}))

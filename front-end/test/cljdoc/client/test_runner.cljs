(ns cljdoc.client.test-runner
  (:require [cljdoc.client.single-docset-search.logic-test]
            [cljs.test :as t]))

(defmethod t/report [:cljs.test/default :begin-test-var] [m]
  (let [test-name (-> m :var meta :name)]
    (println "===" test-name)))

(t/run-tests 'cljdoc.client.single-docset-search.logic-test)

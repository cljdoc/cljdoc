(ns cljdoc.client.test-runner
  (:require [cljdoc.client.single-docset-search.logic-test]
            [cljs.test :as t]))

(t/run-tests 'cljdoc.client.single-docset-search.logic-test)

(ns cljdoc.util.ns-tree-test
  (:require [cljdoc.util.ns-tree :as ns-tree]
            [clojure.test :as t]))

(t/deftest replant-ns-test
  (t/is (= "my.app.routes" (ns-tree/replant-ns "my.app.core" "routes")))
  (t/is (= "my.app.api.routes" (ns-tree/replant-ns "my.app.core" "api.routes")))
  (t/is (= "my.app.api.handlers" (ns-tree/replant-ns "my.app.core" "my.app.api.handlers"))))

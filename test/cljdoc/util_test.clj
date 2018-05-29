(ns cljdoc.util-test
  (:require [cljdoc.util :as util]
            [clojure.test :as t]))

(t/deftest gh-coordinate-test
  (t/is (= "circleci" (util/gh-owner "https://github.com/circleci/clj-yaml")))
  (t/is (= "clj-yaml" (util/gh-repo "https://github.com/circleci/clj-yaml")))
  (t/is (= "circleci/clj-yaml" (util/gh-coordinate "https://github.com/circleci/clj-yaml"))))

(comment
  (t/run-tests)


  )

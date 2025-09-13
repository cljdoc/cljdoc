(ns cljdoc.util.version-test
  (:require [cljdoc.util.version :as version]
            [clojure.test :as t]
            [matcher-combinators.matchers :as m]
            [matcher-combinators.test]))

(t/deftest sorts-version-strings-test
  (t/is (match? ["2.0.0"
                 "2-beta54"
                 "2-beta54-SNAPSHOT"
                 "2-beta54-rc1"
                 "2-beta54-alpha3"
                 "2-beta54-alpha2.1"
                 "2-beta54-alpha2"
                 "2-beta54-alpha1"
                 "2-beta53"
                 "2-beta52"
                 "2-beta51"
                 "2-beta50"
                 "2-beta49"
                 "2-beta40"
                 "2-beta39"
                 "2-beta38"
                 "2-beta37"
                 "2-beta36"
                 "2-beta35"
                 "2-beta34"
                 "2-beta33"
                 "2-beta32"
                 "2-beta31.1"
                 "2-beta30"
                 "2-beta29"
                 "2-beta28"
                 "1.0.0"]
                (->> ["1.0.0"
                      "2.0.0"
                      "2-beta31.1"
                      "2-beta54"
                      "2-beta54-SNAPSHOT"
                      "2-beta54-alpha1"
                      "2-beta54-alpha2.1"
                      "2-beta54-alpha2"
                      "2-beta54-alpha3"
                      "2-beta54-rc1"
                      "2-beta53"
                      "2-beta52"
                      "2-beta51"
                      "2-beta50"
                      "2-beta49"
                      "2-beta40"
                      "2-beta39"
                      "2-beta38"
                      "2-beta37"
                      "2-beta36"
                      "2-beta35"
                      "2-beta34"
                      "2-beta33"
                      "2-beta32"
                      "2-beta30"
                      "2-beta29"
                      "2-beta28"]
                     shuffle
                     (sort version/version-compare)))))

(t/deftest sort-by-version-test
  (t/is (match? (m/nested-equals
                 [{:foo 2 :version "3.0.0"}
                  {:foo 4 :version "2.0.2-SNAPSHOT"}
                  {:foo 3 :version "2.0.1"}
                  {:foo 1 :version "1.0.0"}
                  {:foo 6 :version "0.3.0-beta"}
                  {:foo 5 :version "0.3.0-alpha"}])
                (->> [{:foo 1 :version "1.0.0"}
                      {:foo 2 :version "3.0.0"}
                      {:foo 3 :version "2.0.1"}
                      {:foo 4 :version "2.0.2-SNAPSHOT"}
                      {:foo 5 :version "0.3.0-alpha"}
                      {:foo 6 :version "0.3.0-beta"}]
                     shuffle
                     (sort-by :version version/version-compare)))))

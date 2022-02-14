(ns cljdoc.util.repositories-test
  (:require [cljdoc.util.repositories :as repositories]
            [clojure.test :as t])
  (:import (clojure.lang ExceptionInfo)))

(t/deftest find-artifact-repository-test
  (let [central "https://repo.maven.apache.org/maven2/"
        clojars "https://repo.clojars.org/"]
    (t/is (= (repositories/find-artifact-repository "org.clojure/clojure" "1.9.0")
             central))
    (t/is (false? (repositories/exists? central 'bidi "2.1.3-SNAPSHOT")))
    (t/is (true? (repositories/exists? clojars 'bidi "2.0.9-SNAPSHOT")))
    (t/is (true? (repositories/exists? clojars 'bidi "2.0.0")))
    (t/is (true? (repositories/exists? central 'org.clojure/clojure "1.9.0")))
    (t/is (true? (repositories/exists? clojars 'bidi)))
    (t/is (true? (repositories/exists? clojars 'org.clojure/clojure)))
    (t/is (true? (repositories/exists? central 'org.clojure/clojure))))
  (t/is (thrown-with-msg? ExceptionInfo #"Requested version cannot be found in configured repositories"
                          (repositories/artifact-uris 'bidi "2.1.3-SNAPSHOT")))
  (t/is (= (repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT")
           {:pom "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.pom",
            :jar "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.jar"})))

(t/deftest latest-release-test
  (t/is (= "0.0.5" (repositories/latest-release-version "org/clojure/math.numeric-tower"))))

(comment
  (t/run-tests))

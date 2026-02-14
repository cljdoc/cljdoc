(ns cljdoc.util.repositories-test
  (:require [cljdoc.util.repositories :as repositories]
            [clojure.test :as t]))

(t/deftest find-artifact-repository-test
  (let [clojars "https://repo.clojars.org/"
        central "https://repo.maven.apache.org/maven2/"
        maven-repos [{:id "clojars" :url clojars}
                     {:id "central" :url central}]]
    (t/is (= (repositories/find-artifact-repository maven-repos "org.clojure/clojure" "1.9.0")
             central))
    (t/is (false? (repositories/exists? central 'bidi "2.1.3-SNAPSHOT")))
    (t/is (true? (repositories/exists? clojars 'bidi "2.0.9-SNAPSHOT")))
    (t/is (true? (repositories/exists? clojars 'bidi "2.0.0")))
    (t/is (true? (repositories/exists? central 'org.clojure/clojure "1.9.0")))
    (t/is (true? (repositories/exists? clojars 'bidi)))
    (t/is (false? (repositories/exists? clojars 'org.clojure/clojure)))
    (t/is (true? (repositories/exists? central 'org.clojure/clojure)))
    (t/is (= nil (repositories/artifact-uris maven-repos 'bidi "2.1.3-SNAPSHOT")))
    (t/is (= (repositories/artifact-uris maven-repos 'bidi "2.0.9-SNAPSHOT")
             {:pom "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.pom",
              :jar "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.jar"}))))

(t/deftest latest-release-test
  (t/is (= "0.1.1" (repositories/latest-release-version
                    [{:id "clojars" :url "https://repo.clojars.org/"}
                     {:id "central" :url "https://repo.maven.apache.org/maven2/"}]
                    "org/clojure/math.numeric-tower"))))

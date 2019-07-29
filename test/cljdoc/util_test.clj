(ns cljdoc.util-test
  (:require [cljdoc.util :as util]
            [cljdoc.util.repositories :as repositories]
            [cljdoc.util.datetime :as dt]
            [clojure.test :as t])
  (:import (clojure.lang ExceptionInfo)
           (java.io StringReader)))

(t/deftest find-artifact-repository-test
  (let [central "http://central.maven.org/maven2/"
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
  (t/is (= "0.0.4" (repositories/latest-release-version "org/clojure/math.numeric-tower"))))

(t/deftest normalize-git-url-test
  (t/is (= (util/normalize-git-url "git@github.com:clojure/clojure.git")
           "https://github.com/clojure/clojure"))
  (t/is (= (util/normalize-git-url "http://github.com/clojure/clojure.git")
           "https://github.com/clojure/clojure"))
  (t/is (= (util/normalize-git-url "http://github.com/clojure/clojure")
           "https://github.com/clojure/clojure")))

(t/deftest relativize-path-test
  (t/is (= "xyz.html" (util/relativize-path "doc/abc.html" "doc/xyz.html")))
  (t/is (= "common-xyz.html" (util/relativize-path "doc/common-abc.html" "doc/common-xyz.html")))
  (t/is (= "common-xyz/test.html" (util/relativize-path "doc/common-xyz.html" "doc/common-xyz/test.html")))
  (t/is (= "a/b.html" (util/relativize-path "a/b.html" "a/a/b.html")))
  (t/is (= "../basics/route-syntax" (util/relativize-path "/doc/http/pedestal"
                                                          "/doc/basics/route-syntax")))
  (t/is (= "../../basics/route-syntax/even-more" (util/relativize-path "/doc/http/pedestal/more"
                                                                       "/doc/basics/route-syntax/even-more")))
  (t/is (= "walk-through/asd" (util/relativize-path "/doc/introduction" "/doc/introduction/walk-through/asd")))
  (t/is (= "../.." (util/relativize-path "/doc/introduction/walk-through/asd" "/doc/introduction")))
  (t/is (= ".." (util/relativize-path "/doc/introduction/walk-through" "/doc/introduction"))))
(t/deftest replant-ns-test
  (t/is (= "my.app.routes" (util/replant-ns "my.app.core" "routes")))
  (t/is (= "my.app.api.routes" (util/replant-ns "my.app.core" "api.routes")))
  (t/is (= "my.app.api.handlers" (util/replant-ns "my.app.core" "my.app.api.handlers"))))

(t/deftest serialize-read-cljdoc-edn
  (t/is (= "{:or #regex \"^Test*\"}" (util/serialize-cljdoc-edn {:or #"^Test*"})))
    ;; we need to compare the resulting string as two regex are equal (= #"" #"") => false
  (t/is (= (str {:or #"^Test*"}) (str (util/read-cljdoc-edn (StringReader. "{:or #regex \"^Test*\"}"))))))

(t/deftest day-suffix-test
  (t/is (= "st" (dt/day-suffix 1)))
  (t/is (= "nd" (dt/day-suffix 2)))
  (t/is (= "rd" (dt/day-suffix 3)))
  (t/is (= "th" (dt/day-suffix 15))))

(t/deftest analytics-format-test
  (t/is (= "Wed, Oct 17th" (dt/->analytics-format "2018-10-17T20:58:21.491730Z"))))

(comment
  (t/run-tests))

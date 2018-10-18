(ns cljdoc.util-test
  (:require [cljdoc.util :as util]
            [cljdoc.util.repositories :as repositories]
            [clojure.test :as t])
  (:import (clojure.lang ExceptionInfo)
           (java.io StringReader)))

(t/deftest scm-coordinate-test
  (t/is (= "circleci" (util/scm-owner "https://github.com/circleci/clj-yaml")))
  (t/is (= "eval" (util/scm-owner "https://gitlab.com/eval/otarta")))
  (t/is (= "clj-yaml" (util/scm-repo "https://github.com/circleci/clj-yaml")))
  (t/is (= "otarta" (util/scm-repo "https://gitlab.com/eval/otarta")))
  (t/is (= "circleci/clj-yaml" (util/scm-coordinate "https://github.com/circleci/clj-yaml")))
  (t/is (= "eval/otarta" (util/scm-coordinate "https://gitlab.com/eval/otarta"))))

(t/deftest scm-provider-test
  (t/is (= :github (util/scm-provider "https://github.com/circleci/clj-yaml")))
  (t/is (= :gitlab (util/scm-provider "https://gitlab.com/eval/otarta")))
  (t/is (= nil (util/scm-provider "https://unknown-scm.com/circleci/clj-yaml"))))

(t/deftest find-artifact-repository-test
  (t/is (= (repositories/find-artifact-repository "org.clojure/clojure" "1.9.0")
           repositories/maven-central))
  (t/is (false? (repositories/exists? repositories/maven-central 'bidi "2.1.3-SNAPSHOT")))
  (t/is (true? (repositories/exists? repositories/clojars 'bidi "2.0.9-SNAPSHOT")))
  (t/is (true? (repositories/exists? repositories/clojars 'bidi "2.0.0")))
  (t/is (true? (repositories/exists? repositories/maven-central 'org.clojure/clojure "1.9.0")))
  (t/is (true? (repositories/exists? repositories/clojars 'bidi)))
  (t/is (true? (repositories/exists? repositories/clojars 'org.clojure/clojure)))
  (t/is (true? (repositories/exists? repositories/maven-central 'org.clojure/clojure)))
  (t/is (thrown-with-msg? ExceptionInfo #"Requested version cannot be found on Clojars or Maven Central"
                          (repositories/artifact-uris 'bidi "2.1.3-SNAPSHOT")))
  (t/is (= (repositories/artifact-uris 'bidi "2.0.9-SNAPSHOT")
           {:pom "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.pom",
            :jar "https://repo.clojars.org/bidi/bidi/2.0.9-SNAPSHOT/bidi-2.0.9-20160426.224252-1.jar"})))

(t/deftest latest-release-test
  (t/is (= "0.0.4" (repositories/latest-release-version 'org/clojure/math.numeric-tower))))

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
  (t/is (= "a/b.html" (util/relativize-path "a/b.html" "a/a/b.html"))))

(t/deftest replant-ns-test
  (t/is (= "my.app.routes" (util/replant-ns "my.app.core" "routes")))
  (t/is (= "my.app.api.routes" (util/replant-ns "my.app.core" "api.routes")))
  (t/is (= "my.app.api.handlers" (util/replant-ns "my.app.core" "my.app.api.handlers"))))

(t/deftest serialize-read-cljdoc-edn
    (t/is (= "{:or #regex \"^Test*\"}" (util/serialize-cljdoc-edn {:or #"^Test*"})))
    ;; we need to compare the resulting string as two regex are equal (= #"" #"") => false
    (t/is (= (str {:or #"^Test*"}) (str (util/read-cljdoc-edn (StringReader. "{:or #regex \"^Test*\"}"))))))

(comment
  (t/run-tests)

  )

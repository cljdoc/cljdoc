(ns cljdoc.util.fixref-test
  (:require [clojure.test :as t]
            [cljdoc.util.fixref :as fixref]))

(t/deftest link-fix-test
  (t/is (= "/d/a/b/1.0.0/doc/getting-started/friendly-sql-functions#some-thing"
           (fixref/fix-link
            "doc/getting-started.md"
            "friendly-sql-functions.md#some-thing"
            {:scm-base "https://github.com/a/b/blob/v1.0.0/"
             :uri-map {"doc/friendly-sql-functions.md" "/d/a/b/1.0.0/doc/getting-started/friendly-sql-functions"}}))))


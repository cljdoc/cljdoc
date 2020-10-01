(ns cljdoc.analysis.git-test
  (:require [cljdoc.analysis.git :as git-ana]
            [clojure.test :as t]))

(t/deftest ^:slow version-tag-test
  (let [analysis (git-ana/analyze-git-repo "metosin/reitit" "0.1.1" "https://github.com/metosin/reitit" nil)]
    (t/is (= "0.1.1" (-> analysis :scm :tag :name)))
    ;; This will fail if the tag is modified (it shouldn't be)
    (t/is (= "c210e7f1c2f8a163676c8e3abeab8e50951458bb"
             (-> analysis :scm :tag :commit)))))

(comment
  (t/run-tests))

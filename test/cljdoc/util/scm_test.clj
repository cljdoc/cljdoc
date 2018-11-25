(ns cljdoc.util.scm-test
  (:require [clojure.test :as t]
            [cljdoc.util.scm :as scm]))

(t/deftest scm-coordinate-test
  (t/is (= "circleci" (scm/owner "https://github.com/circleci/clj-yaml")))
  (t/is (= "eval" (scm/owner "https://gitlab.com/eval/otarta")))
  (t/is (= "clj-yaml" (scm/repo "https://github.com/circleci/clj-yaml")))
  (t/is (= "otarta" (scm/repo "https://gitlab.com/eval/otarta")))
  (t/is (= "circleci/clj-yaml" (scm/coordinate "https://github.com/circleci/clj-yaml")))
  (t/is (= "eval/otarta" (scm/coordinate "https://gitlab.com/eval/otarta")))
  (t/is (= "eval/otarta" (scm/coordinate "https://git@gitlab.com/eval/otarta")))
  (t/is (= "josha/formulare" (scm/coordinate "https://gitea.heevyis.ninja/josha/formulare"))))

(t/deftest scm-provider-test
  (t/is (= :github (scm/provider "https://github.com/circleci/clj-yaml")))
  (t/is (= :github (scm/provider "https://www.github.com/cloverage/cloverage")))
  (t/is (= :github (scm/provider "https://git@www.github.com/cloverage/cloverage")))
  (t/is (= :gitlab (scm/provider "https://gitlab.com/eval/otarta")))
  (t/is (= nil (scm/provider "https://gitea.heevyis.ninja/josha/formulare")))
  (t/is (= nil (scm/provider "https://unknown-scm.com/circleci/clj-yaml"))))

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
  (t/is (= "josha/formulare" (scm/coordinate "https://gitea.heevyis.ninja/josha/formulare")))
  (t/is (= "lread/muckabout" (scm/coordinate "https://codeberg.org/lread/muckabout")))
  (t/is (= "dtolpin/anglican" (scm/coordinate "https://bitbucket.org/dtolpin/anglican"))))

(t/deftest scm-provider-test
  (t/is (= :github (scm/provider "https://github.com/circleci/clj-yaml")))
  (t/is (= :github (scm/provider "https://www.github.com/cloverage/cloverage")))
  (t/is (= :github (scm/provider "https://git@www.github.com/cloverage/cloverage")))
  (t/is (= :gitlab (scm/provider "https://gitlab.com/eval/otarta")))
  (t/is (= :sourcehut (scm/provider "https://git.sr.ht/~miikka/clj-branca")))
  (t/is (= :gitea (scm/provider "https://gitea.heevyis.ninja/josha/formulare")))
  (t/is (= :codeberg (scm/provider "https://codeberg.org/lread/muckabout")))
  (t/is (= :bitbucket (scm/provider "https://bitbucket.org/dtolpin/anglican")))
  (t/is (= nil (scm/provider "https://unknown-scm.com/circleci/clj-yaml"))))

(t/deftest rev-formatted-base-url
  (t/is (= "https://github.com/user/repo/blob/SHA/"
           (scm/rev-formatted-base-url {:url "https://github.com/user/repo" :commit "SHA"})))
  (t/is (= "https://github.com/user/repo/blob/TAG/"
           (scm/rev-formatted-base-url {:url "https://github.com/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://gitlab.com/user/repo/blob/SHA/"
           (scm/rev-formatted-base-url {:url "https://gitlab.com/user/repo" :commit "SHA"})))
  (t/is (= "https://gitlab.com/user/repo/blob/TAG/"
           (scm/rev-formatted-base-url {:url "https://gitlab.com/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://unknown.service/user/repo/blob/SHA/"
           (scm/rev-formatted-base-url {:url "https://unknown.service/user/repo" :commit "SHA"})))
  (t/is (= "https://unknown.service/user/repo/blob/TAG/"
           (scm/rev-formatted-base-url {:url "https://unknown.service/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://sr.ht/user/repo/tree/SHA/"
           (scm/rev-formatted-base-url {:url "https://sr.ht/user/repo" :commit "SHA"})))
  (t/is (= "https://sr.ht/user/repo/tree/TAG/"
           (scm/rev-formatted-base-url {:url "https://sr.ht/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://codeberg.org/user/repo/src/commit/SHA/"
           (scm/rev-formatted-base-url {:url "https://codeberg.org/user/repo" :commit "SHA"})))
  (t/is (= "https://codeberg.org/user/repo/src/tag/TAG/"
           (scm/rev-formatted-base-url {:url "https://codeberg.org/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://gitea.some.org/user/repo/src/commit/SHA/"
           (scm/rev-formatted-base-url {:url "https://gitea.some.org/user/repo" :commit "SHA"})))
  (t/is (= "https://gitea.some.org/user/repo/src/tag/TAG/"
           (scm/rev-formatted-base-url {:url "https://gitea.some.org/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://bitbucket.org/user/repo/src/SHA/"
           (scm/rev-formatted-base-url {:url "https://bitbucket.org/user/repo" :commit "SHA"})))
  (t/is (= "https://bitbucket.org/user/repo/src/TAG/"
           (scm/rev-formatted-base-url {:url "https://bitbucket.org/user/repo" :commit "SHA" :tag {:name "TAG"}}))))

(t/deftest rev-raw-base-url
  (t/is (= "https://github.com/user/repo/raw/SHA/"
           (scm/rev-raw-base-url {:url "https://github.com/user/repo" :commit "SHA"})))
  (t/is (= "https://github.com/user/repo/raw/TAG/"
           (scm/rev-raw-base-url {:url "https://github.com/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://gitlab.com/user/repo/raw/SHA/"
           (scm/rev-raw-base-url {:url "https://gitlab.com/user/repo" :commit "SHA"})))
  (t/is (= "https://gitlab.com/user/repo/raw/TAG/"
           (scm/rev-raw-base-url {:url "https://gitlab.com/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://unknown.service/user/repo/raw/SHA/"
           (scm/rev-raw-base-url {:url "https://unknown.service/user/repo" :commit "SHA"})))
  (t/is (= "https://unknown.service/user/repo/raw/TAG/"
           (scm/rev-raw-base-url {:url "https://unknown.service/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://sr.ht/user/repo/blob/SHA/"
           (scm/rev-raw-base-url {:url "https://sr.ht/user/repo" :commit "SHA"})))
  (t/is (= "https://sr.ht/user/repo/blob/TAG/"
           (scm/rev-raw-base-url {:url "https://sr.ht/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://codeberg.org/user/repo/raw/commit/SHA/"
           (scm/rev-raw-base-url {:url "https://codeberg.org/user/repo" :commit "SHA"})))
  (t/is (= "https://codeberg.org/user/repo/raw/tag/TAG/"
           (scm/rev-raw-base-url {:url "https://codeberg.org/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://gitea.some.org/user/repo/raw/commit/SHA/"
           (scm/rev-raw-base-url {:url "https://gitea.some.org/user/repo" :commit "SHA"})))
  (t/is (= "https://gitea.some.org/user/repo/raw/tag/TAG/"
           (scm/rev-raw-base-url {:url "https://gitea.some.org/user/repo" :commit "SHA" :tag {:name "TAG"}})))
  (t/is (= "https://bitbucket.org/user/repo/raw/SHA/"
           (scm/rev-raw-base-url {:url "https://bitbucket.org/user/repo" :commit "SHA"})))
  (t/is (= "https://bitbucket.org/user/repo/raw/TAG/"
           (scm/rev-raw-base-url {:url "https://bitbucket.org/user/repo" :commit "SHA" :tag {:name "TAG"}}))))

(t/deftest scm-uri-inversion-test-to-ssh
  (t/is (= "git@github.com:circleci/clj-yaml.git" (scm/ssh-uri "https://github.com/circleci/clj-yaml")))
  (t/is (= "git@gitea.heevyis.ninja:josha/formulare.git" (scm/ssh-uri "https://gitea.heevyis.ninja/josha/formulare")))
  (t/is (= "git@unknown-scm.com:circleci/clj-yaml.git" (scm/ssh-uri "https://unknown-scm.com/circleci/clj-yaml"))))

(t/deftest scm-uri-inversion-test-to-http
  (t/is (= "http://github.com/circleci/clj-yaml" (scm/http-uri "git@github.com:circleci/clj-yaml.git")))
  (t/is (= "http://gitea.heevyis.ninja/josha/formulare" (scm/http-uri "git@gitea.heevyis.ninja:josha/formulare.git")))
  (t/is (= "http://unknown-scm.com/circleci/clj-yaml" (scm/http-uri "git@unknown-scm.com:circleci/clj-yaml"))))

(t/deftest http-uri-remove-extra-segments
  (t/is (= "http://github.com/circleci/clj-yaml" (scm/http-uri "http://github.com/circleci/clj-yaml/some/extra/stuff")))
  (t/is (= "https://github.com/circleci/clj-yaml" (scm/http-uri "https://github.com/circleci/clj-yaml/some/extra/stuff"))))

(t/deftest scm-view-uri-test
  (t/is (= "https://github.com/circleci/clj-yaml/blob/master/README.md" (scm/branch-url {:url "https://github.com/circleci/clj-yaml", :branch "master"} "README.md")))
  (t/is (= "https://git.sr.ht/~miikka/clj-branca/tree/master/README.md" (scm/branch-url {:url "https://git.sr.ht/~miikka/clj-branca", :branch "master"} "README.md")))
  (t/is (= "https://codeberg.org/lread/muckabout/src/branch/main/README.md" (scm/branch-url {:url "https://codeberg.org/lread/muckabout", :branch "main"} "README.md")))
  (t/is (= "https://bitbucket.org/dtolpin/anglican/src/master/README.md" (scm/branch-url {:url "https://bitbucket.org/dtolpin/anglican" :branch "master"} "README.md"))))

(t/deftest normalize-git-url-test
  (t/is (= (scm/normalize-git-url "git@github.com:clojure/clojure.git")
           "https://github.com/clojure/clojure"))
  (t/is (= (scm/normalize-git-url "http://github.com/clojure/clojure.git")
           "https://github.com/clojure/clojure"))
  (t/is (= (scm/normalize-git-url "http://github.com/clojure/clojure")
           "https://github.com/clojure/clojure")))

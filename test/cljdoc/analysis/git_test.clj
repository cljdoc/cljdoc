(ns cljdoc.analysis.git-test
  (:require [cljdoc.analysis.git :as git-ana]
            [clojure.test :as t]
            [matcher-combinators.test]
            [matcher-combinators.matchers :as m]))

(t/deftest ^:slow version-tag-test
  (t/is (match?
          {:scm {:tag {:name "0.1.1"
                       :commit "c210e7f1c2f8a163676c8e3abeab8e50951458bb"}}}
          (git-ana/analyze-git-repo "metosin/reitit" "0.1.1" "https://github.com/metosin/reitit" nil))))

(t/deftest ^:slow without-article-amendment-tag-test
  (t/is (match?
          {:scm {:tag {:name "v1.0.0"}}
           :scm-articles m/absent
           :config (m/equals {})
           :doc-tree [{:title "Readme" :attrs {:cljdoc/markdown #"version: v1.0.0"}}
                      {:title "Inferred Title doca" :attrs {:cljdoc/markdown #"version: v1.0.0"}}
                      {:title "Inferred Title docb" :attrs {:cljdoc/asciidoc #"version: v1.0.0"}}]}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.0" "https://github.com/cljdoc/cljdoc-test-repo" nil))))

(t/deftest ^:slow with-article-amendment-tag-test
  (t/is (match?
          {:scm {:tag {:name "v1.0.1"}}
           :scm-articles {:tag {:name "cljdoc-v1.0.1"}}
           :config (m/equals {:cljdoc.doc/tree [[ "Readme" { :file "README.md" } ]
                                                [ "explicit doca title" { :file "doc/doca.md" } ]
                                                [ "explicit docb title" { :file "doc/docb.adoc" } ] ] })
           :doc-tree [{:title "Readme" :attrs {:cljdoc/markdown #"version: cljdoc-v1.0.1"}}
                      {:title "explicit doca title" :attrs {:cljdoc/markdown #"version: cljdoc-v1.0.1"}}
                      {:title "explicit docb title" :attrs {:cljdoc/asciidoc #"version: cljdoc-v1.0.1"}}]}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.1" "https://github.com/cljdoc/cljdoc-test-repo" nil))))

(t/deftest ^:slow version-after-article-amendment-tag-test
  (t/is (match?
          {:scm {:tag {:name "v1.0.2"}}
           :scm-articles m/absent
           :config (m/equals {})
           :doc-tree [{:title "Readme" :attrs {:cljdoc/markdown #"version: v1.0.2"}}
                      {:title "Inferred Title doca" :attrs {:cljdoc/markdown #"version: v1.0.2"}}
                      {:title "Inferred Title docb" :attrs {:cljdoc/asciidoc #"version: v1.0.2"}}]}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.2" "https://github.com/cljdoc/cljdoc-test-repo" nil))))

(t/deftest ^:slow pom-scm-tag-does-not-exclude-version-tags-test
  (t/is (match?
          {:scm {:tag {:name "v1.0.1"}
                 :rev "a9d0465e417afe03de0fcc354e845691a758b26a"}
           :scm-articles {:tag {:name "cljdoc-v1.0.1"}}}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.1" "https://github.com/cljdoc/cljdoc-test-repo"
                                    "a9d0465e417afe03de0fcc354e845691a758b26a"))))

(t/deftest ^:slow snapshot-version-with-pom-scm-tag-test
  (t/is (match?
          {:scm {:tag m/absent
                 :rev "a9d0465e417afe03de0fcc354e845691a758b26a"}
           :scm-articles m/absent}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.1-SNAPSHOT" "https://github.com/cljdoc/cljdoc-test-repo"
                                    "a9d0465e417afe03de0fcc354e845691a758b26a"))))

(t/deftest ^:slow snapshot-version-without-pom-scm-tag-test
  (t/is (match?
          {:scm {:tag m/absent
                 :rev "main"}
           :scm-articles m/absent}
          (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.1-SNAPSHOT" "https://github.com/cljdoc/cljdoc-test-repo"
                                    nil))))
(comment

  ;; no ammendments
  (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.0" "https://github.com/cljdoc/cljdoc-test-repo" nil)

  ;; amendments
  (git-ana/analyze-git-repo "org.cljdoc/cljdoc-test-repo" "1.0.2" "https://github.com/cljdoc/cljdoc-test-repo" nil)


  (t/run-tests))

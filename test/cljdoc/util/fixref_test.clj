(ns cljdoc.util.fixref-test
  (:require [clojure.string :as string]
            [clojure.test :as t]
            [cljdoc.util.fixref :as fixref]))

(defn- fix-result
  "splitting result into a vector of lines makes for much better failure diffs"
  [lines]
  (map string/trim (string/split lines #"\n")))

;; some reasonable defaults for our tests
(def fix-opts {:scm-file-path "doc/path.adoc"
               :scm {:commit "#SHA#"
                     :url "https://scm/user/project"}
               :uri-map {}})

(t/deftest fix-test
  (t/testing "favors git tag representing version, when present, over commit"
    (t/is (= ["<a href=\"https://scm/user/project/blob/v1.2.3/doc/doc.md\" rel=\"nofollow\">my doc</a>"
              "<img src=\"https://scm/user/project/raw/v1.2.3/doc/images/one.png\">"]
             (fix-result (fixref/fix (str "<a href=\"doc.md\">my doc</a>"
                                          "<img src=\"images/one.png\">")
                                     (assoc-in fix-opts [:scm :tag :name] "v1.2.3"))))))

  (t/testing "ignores"
    (t/testing "rendered wikilinks (which can occur in docstrings)"
      (let [html ["<a href=\"down/deeper/to/doc.adoc\" data-source=\"wikilink\">relative link</a>"
                  "<a href=\"../upone/norm1.adoc\" data-source=\"wikilink\">norm1</a>"
                  "<a href=\"../../norm2.adoc\" data-source=\"wikilink\">norm2</a>"]]
        (t/is (= html (fix-result (fixref/fix (string/join "\n" html)
                                              fix-opts))))))

    (t/testing "absolute image refs"
      (let [html ["<img src=\"https://svgworld.com/abs.svg\">"
                  "<img src=\"https://cljdoc.org/some/path/absolute-cljdoc.png\">"
                  "<img src=\"https://clojure.org/images/clojure-logo-120b.png\">"]]
        (t/is (= html (fix-result (fixref/fix (string/join "\n" html)
                                              fix-opts))))))
    (t/testing "anchor links"
      (let [html ["<a href=\"#anchor\">anchor link</a>"]]
        (t/is (= html (fix-result (fixref/fix (string/join "\n" html)
                                              fix-opts)))))))

  (t/testing "renders error ref"
    (t/testing "when scm file path is unknown (as is case from docstrings) and a relative path is specified"
      (t/is (= ["<a href=\"#!cljdoc-error!ref-must-be-root-relative!\">link text</a>"
                "<img src=\"#!cljdoc-error!ref-must-be-root-relative!\">"]
               (fix-result
                (fixref/fix (str "<a href=\"rel/ref/here.md\">link text</a>"
                                 "<img src=\"rel/ref/here.png\">")
                            (dissoc fix-opts :scm-file-path)))))))

  (t/testing "external links"
    (t/testing "include nofollow"
      (t/is (= ["<a href=\"https://clojure.org\" rel=\"nofollow\">absolute link elsewhere</a>"
                "<a href=\"http://unsecure.com\" rel=\"nofollow\">absolutely insecure</a>"]
               (fix-result
                (fixref/fix (str "<a href=\"https://clojure.org\">absolute link elsewhere</a>"
                                 "<a href=\"http://unsecure.com\">absolutely insecure</a>")
                            fix-opts))))))

  (t/testing "cljdoc absolute links"
    (t/testing "are converted to cljdoc root relative to support local testing"
      (t/is (= ["<a href=\"/some/path/here\">absolute link to cljdoc</a>"
                "<a href=\"/another/path\">absolute link to cljdoc.xyz</a>"]
               (fix-result
                (fixref/fix (str "<a href=\"https://cljdoc.org/some/path/here\">absolute link to cljdoc</a>"
                                 "<a href=\"https://cljdoc.xyz/another/path\">absolute link to cljdoc.xyz</a>")
                            fix-opts))))))

 (t/testing "unknown scm links"
    (t/testing "when relative, will point to normalized scm"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/doc/path/down/deeper/to/doc.adoc\" rel=\"nofollow\">relative link</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/doc/upone/norm1.adoc\" rel=\"nofollow\">norm1</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/norm2.adoc\" rel=\"nofollow\">norm2</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/../../../norm2.adoc\" rel=\"nofollow\">norm2</a>"]
               (fix-result
                (fixref/fix (str "<a href=\"down/deeper/to/doc.adoc\">relative link</a>"
                                 "<a href=\"../upone/norm1.adoc\">norm1</a>"
                                 "<a href=\"../../norm2.adoc\">norm2</a>"
                                 "<a href=\"../../../../../norm2.adoc\">norm2</a>" )
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc"))))))
    (t/testing "when root relative, will point to normalized scm project root"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/root/relative/doc.md\" rel=\"nofollow\">root relative link</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/root/a/d/doc.md\" rel=\"nofollow\">root relative link</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/doc.md\" rel=\"nofollow\">root relative link</a>"]
               (fix-result
                (fixref/fix (str "<a href=\"/root/relative/doc.md\">root relative link</a>"
                                 "<a href=\"/root/./././relative/../a/b/c/../../d/doc.md\">root relative link</a>"
                                 "<a href=\"/root/relative/../../../../../doc.md\">root relative link</a>")
                            fix-opts))))))

 (t/testing "known scm relative links (imported articles)"
    (t/testing  "are adjusted to point to article slugs"
      (t/is (= ["<a href=\"slugged-doc\">slug converted</a>"]
               (fix-result
                (fixref/fix "<a href=\"slug/conversion/slugtest.adoc\">slug converted</a>"
                            (assoc fix-opts
                                   :scm-file-path "doc/path/my-doc.adoc"
                                   :uri-map {"doc/path/slug/conversion/slugtest.adoc" "slugged-doc"}))))))
    (t/testing "can point to html files for offline bundles and support rewriting to different structure via target path"
      (t/are [?target-path ?expected-html]
          (t/is (= ?expected-html (fix-result
                                     (fixref/fix "<a href=\"mapped.adoc\">offline doc</a>"
                                                 (assoc fix-opts
                                                        :scm-file-path "doc/path/my-doc.adoc"
                                                        :target-path   ?target-path
                                                        :uri-map       {"doc/path/mapped.adoc" "doc/offline.html"})))))
        ""    ["<a href=\"doc/offline.html\">offline doc</a>"]
        "doc" ["<a href=\"offline.html\">offline doc</a>"]
        "api" ["<a href=\"../doc/offline.html\">offline doc</a>"])))

  (t/testing "scm images"
    (t/testing "when relative, will point to normalized scm raw ref"
      (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/doc/path/rel1.png\">"
                "<img src=\"https://scm/user/project/raw/#SHA#/images/rel2.png\">"
                "<img src=\"https://scm/user/project/raw/#SHA#/homages/rel3.png\">"
                "<img src=\"https://scm/user/project/raw/#SHA#/../../../../../../../../rel4.png\">" ]
               (fix-result
                (fixref/fix (str "<img src=\"rel1.png\">"
                                 "<img src=\"../../images/rel2.png\">"
                                 "<img src=\"../../images/../homages/./././rel3.png\">"
                                 "<img src=\"../../../../../../../../../../rel4.png\">" )
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc") )))))
    (t/testing "when root relative, will point point to scm raw ref"
      (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/root/relative/image.png\">"
                "<img src=\"https://scm/user/project/raw/#SHA#/root/relative/.././image.png\">"]
               (fix-result
                (fixref/fix (str "<img src=\"/root/relative/image.png\">"
                                 "<img src=\"/root/relative/.././image.png\">")
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc"))))))
    (t/testing "can be svg"
      (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/doc/path/rel.svg\">"]
               (fix-result
                (fixref/fix "<img src=\"rel.svg\">"
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc"))))))
    (t/testing "when svg from github, are sanitized"
      (t/is (= ["<img src=\"https://github.com/user/project/raw/#SHA#/doc/path/rel.svg?sanitize=true\">"]
               (fix-result
                (fixref/fix "<img src=\"rel.svg\">"
                            {:scm-file-path "doc/path/doc.adoc"
                             :scm {:commit "#SHA#"
                                   :url "https://github.com/user/project"}
                             :uri-map {}})))))))

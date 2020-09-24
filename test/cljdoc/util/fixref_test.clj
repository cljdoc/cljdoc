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
  (t/testing "ignores"
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
    (t/testing "when relative, will point to scm"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/doc/path/down/deeper/to/doc.adoc\" rel=\"nofollow\">relative link</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/doc/upone/norm1.adoc\" rel=\"nofollow\">norm1</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/norm2.adoc\" rel=\"nofollow\">norm2</a>"]
               (fix-result
                (fixref/fix (str "<a href=\"down/deeper/to/doc.adoc\">relative link</a>"
                                 "<a href=\"../upone/norm1.adoc\">norm1</a>"
                                 "<a href=\"../../norm2.adoc\">norm2</a>")
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc"))))))
    (t/testing "when root relative, will point to scm project root"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/root/relative/doc.md\" rel=\"nofollow\">root relative link</a>"]
               (fix-result
                (fixref/fix "<a href=\"/root/relative/doc.md\">root relative link</a>"
                            fix-opts))))))

 (t/testing "known scm relative links (imported articles)"
    (t/testing  "are adjusted to point to article slugs"
      (t/is (= ["<a href=\"slugged-doc\">slug converted</a>"]
               (fix-result
                (fixref/fix "<a href=\"slug/conversion/slugtest.adoc\">slug converted</a>"
                            (assoc fix-opts
                                   :scm-file-path "doc/path/my-doc.adoc"
                                   :uri-map {"doc/path/slug/conversion/slugtest.adoc" "slugged-doc"}))))))
    (t/testing "can point to html files for offline bundles"
      (t/is (= ["<a href=\"offline.html\">offline doc</a>"]
               (fix-result
                (fixref/fix "<a href=\"mapped.adoc\">offline doc</a>"
                            (assoc fix-opts
                                   :scm-file-path "doc/path/my-doc.adoc"
                                   :uri-map {"doc/path/mapped.adoc" "doc/offline.html"})))))))

  (t/testing "scm images"
    (t/testing "when relative, will point to scm raw ref"
      (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/doc/path/rel1.png\">"
                "<img src=\"https://scm/user/project/raw/#SHA#/images/rel2.png\">"]
               (fix-result
                (fixref/fix (str "<img src=\"rel1.png\">"
                                 "<img src=\"../../images/rel2.png\">")
                            (assoc fix-opts :scm-file-path "doc/path/doc.adoc") )))))
    (t/testing "when root relative, will point point to scm raw ref"
      (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/root/relative/image.png\">"]
               (fix-result
                (fixref/fix "<img src=\"/root/relative/image.png\">"
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

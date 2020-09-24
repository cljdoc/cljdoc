(ns cljdoc.util.fixref-test
  (:require [clojure.string :as string]
            [clojure.test :as t]
            [cljdoc.util.fixref :as fixref]))

(defn- fix-result
  "splitting result into a vector of lines makes for much better failure diffs"
  [lines]
  (map string/trim (string/split lines #"\n")))

(def fix-opts {:scm {:commit "#SHA#"
                     :url "https://scm/user/project"}
               :uri-map {}})

(t/deftest fix-test
  (t/testing "ignores"
    (t/testing "absolute image refs"
      (let [html ["<img src=\"https://svgworld.com/abs.svg\">"
                  "<img src=\"https://cljdoc.org/some/path/absolute-cljdoc.png\">"
                  "<img src=\"https://clojure.org/images/clojure-logo-120b.png\">"]]
        (t/is (= html (fix-result (fixref/fix "doc/path.adoc"
                                              (string/join "\n" html)
                                              fix-opts))))))
    (t/testing "anchor links"
      (let [html ["<a href=\"#anchor\">anchor link</a>"]]
        (t/is (= html (fix-result (fixref/fix "doc/path.adoc"
                                              (string/join "\n" html)
                                              fix-opts)))))))

  (t/testing "external links"
    (t/testing "include nofollow"
      (t/is (= ["<a href=\"https://clojure.org\" rel=\"nofollow\">absolute link elsewhere</a>"
                "<a href=\"http://unsecure.com\" rel=\"nofollow\">absolutely insecure</a>"]
               (fix-result
                (fixref/fix "doc/path.adoc"
                            (str "<a href=\"https://clojure.org\">absolute link elsewhere</a>"
                                 "<a href=\"http://unsecure.com\">absolutely insecure</a>")
                            fix-opts))))))

 (t/testing "unknown scm links"
    (t/testing "when relative, will point to scm"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/doc/path/down/deeper/to/doc.adoc\" rel=\"nofollow\">relative link</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/doc/upone/norm1.adoc\" rel=\"nofollow\">norm1</a>"
                "<a href=\"https://scm/user/project/blob/#SHA#/norm2.adoc\" rel=\"nofollow\">norm2</a>"]
               (fix-result
                (fixref/fix "doc/path/doc.adoc"
                            (str "<a href=\"down/deeper/to/doc.adoc\">relative link</a>"
                                 "<a href=\"../upone/norm1.adoc\">norm1</a>"
                                 "<a href=\"../../norm2.adoc\">norm2</a>")
                            fix-opts)))))
    (t/testing "when root relative, will point to scm project root"
      (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/root/relative/doc.md\" rel=\"nofollow\">root relative link</a>"]
               (fix-result
                (fixref/fix "doc/path.adoc"
                            "<a href=\"/root/relative/doc.md\">root relative link</a>"
                            fix-opts))))))

 (t/testing "known scm relative links (imported articles)"
    (t/testing  "are adjusted to point to article slugs"
      (t/is (= ["<a href=\"slugged-doc\">slug converted</a>"]
               (fix-result
                (fixref/fix "doc/path/doc.adoc"
                            "<a href=\"slug/conversion/slugtest.adoc\">slug converted</a>"
                            (assoc fix-opts
                                   :uri-map {"doc/path/slug/conversion/slugtest.adoc" "slugged-doc"}))))))
    (t/testing "can point to html files for offline bundles"
      (t/is (= ["<a href=\"offline.html\">offline doc</a>"]
               (fix-result
                (fixref/fix "doc/path/my-doc.adoc"
                            "<a href=\"mapped.adoc\">offline doc</a>"
                            (assoc fix-opts
                                   :uri-map {"doc/path/mapped.adoc" "doc/offline.html"})))))))
  (t/testing "scm images"
    (t/testing "when relative, will point to scm raw ref"
      (t/is (= ["<img src=\"https://raw.githubusercontent.com/user/project/#SHA#/doc/path/rel1.png\">"
                "<img src=\"https://raw.githubusercontent.com/user/project/#SHA#/images/rel2.png\">"]
               (fix-result
                (fixref/fix "doc/path/doc.adoc"
                            (str "<img src=\"rel1.png\">"
                                 "<img src=\"../../images/rel2.png\">")
                            fix-opts )))))
    (t/testing "when root relative, will point point to scm raw ref"
      (t/is (= ["<img src=\"https://raw.githubusercontent.com/user/project/#SHA#/root/relative/image.png\">"]
               (fix-result
                (fixref/fix "doc/path/doc.adoc"
                            "<img src=\"/root/relative/image.png\">"
                            fix-opts)))))
    (t/testing "when svg is github sanitized"
      (t/is (= ["<img src=\"https://raw.githubusercontent.com/user/project/#SHA#/doc/path/rel.svg?sanitize=true\">"]
               (fix-result
                (fixref/fix "doc/path/doc.adoc"
                            "<img src=\"rel.svg\">"
                            fix-opts)))))))

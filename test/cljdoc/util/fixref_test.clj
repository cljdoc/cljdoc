(ns cljdoc.util.fixref-test
  (:require [clojure.test :as t]
            [clojure.string :as string]
            [cljdoc.util.fixref :as fixref]))

(t/deftest fix-link-test
  (t/is (= "/d/a/b/1.0.0/doc/getting-started/friendly-sql-functions#some-thing"
           (fixref/fix-link
            "doc/getting-started.md"
            "friendly-sql-functions.md#some-thing"
            {:scm-base "https://github.com/a/b/blob/v1.0.0/"
             :uri-map {"doc/friendly-sql-functions.md" "/d/a/b/1.0.0/doc/getting-started/friendly-sql-functions"}}))))

(defn- fix-result
  "splitting result into a vector of lines makes for much better failure diffs"
  [lines]
  (map string/trim (string/split lines #"\n")))

(def fix-opts {:scm {:commit "#SHA#"
                     :url "https://scm/user/project"}
               :uri-map {}})

(t/deftest fix-ignores-anchor-link
  (t/is (= ["<a href=\"#anchor\">anchor link</a>"]
           (fix-result
            (fixref/fix "doc/path.adoc"
                        "<a href=\"#anchor\">anchor link</a>"
                        fix-opts)))))

(t/deftest fix-adds-nofollow-to-external-links
  (t/is (= ["<a href=\"https://clojure.org\" rel=\"nofollow\">absolute link elsewhere</a>"
            "<a href=\"http://unsecure.com\" rel=\"nofollow\">absolutely insecure</a>"]
           (fix-result
            (fixref/fix "doc/path.adoc"
                        (str
                         "<a href=\"https://clojure.org\">absolute link elsewhere</a>"
                         "<a href=\"http://unsecure.com\">absolutely insecure</a>")
                        fix-opts)))))

(t/deftest fix-resolves-absolute-cljdoc-links-to-root-relative-to-support-testing
  (t/is (= ["<a href=\"/some/path/here\">absolute link to cljdoc</a>"
            "<a href=\"/another/path\">absolute link to cljdoc.xyz</a>"]
           (fix-result
            (fixref/fix "doc/path.adoc"
                        (str
                         "<a href=\"https://cljdoc.org/some/path/here\">absolute link to cljdoc</a>"
                         "<a href=\"https://cljdoc.xyz/another/path\">absolute link to cljdoc.xyz</a>")
                        fix-opts)))))

(t/deftest fix-resolves-relative-links-to-scm
  (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/doc/path/down/deeper/to/doc.adoc\" rel=\"nofollow\">relative link</a>"
            "<a href=\"https://scm/user/project/blob/#SHA#/doc/upone/norm1.adoc\" rel=\"nofollow\">norm1</a>"
            "<a href=\"https://scm/user/project/blob/#SHA#/norm2.adoc\" rel=\"nofollow\">norm2</a>"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        (str
                         "<a href=\"down/deeper/to/doc.adoc\">relative link</a>"
                         "<a href=\"../upone/norm1.adoc\">norm1</a>"
                         "<a href=\"../../norm2.adoc\">norm2</a>")
                         fix-opts)))))

(t/deftest fix-resolves-root-relative-links-to-scm-project-root
  (t/is (= ["<a href=\"https://scm/user/project/blob/#SHA#/root/relative/doc.md\" rel=\"nofollow\">root relative link</a>"]
           (fix-result
            (fixref/fix "doc/path.adoc"
                        "<a href=\"/root/relative/doc.md\">root relative link</a>"
                        fix-opts)))))

(t/deftest fix-has-special-understanding-of-offline-docs
  (t/is (= ["<a href=\"offline.adoc\">offline doc</a>"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<a href=\"mapped.adoc\">offline doc</a>"
                        (assoc fix-opts :uri-map {"doc/path/mapped.adoc" "doc/offline.adoc"}))))))

(t/deftest fix-converts-paths-to-slugs
  (t/is (= ["<a href=\"slugged-doc\">slug converted</a>"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<a href=\"slug/conversion/slugtest.adoc\">slug converted</a>"
                        (assoc fix-opts :uri-map {"doc/path/slug/conversion/slugtest.adoc" "slugged-doc"}))))))

(t/deftest fix-ignores-absolute-image-refs
  (t/is (= ["<img src=\"https://cljdoc.org/some/path/absolute-cljdoc.png\">"
            "<img src=\"https://clojure.org/images/clojure-logo-120b.png\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        (str
                         "<img src=\"https://cljdoc.org/some/path/absolute-cljdoc.png\">"
                         "<img src=\"https://clojure.org/images/clojure-logo-120b.png\">")
                        fix-opts)))))

(t/deftest fix-resolves-relative-images-to-scm-raw-refs
  (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/doc/path/rel1.png\">"
            "<img src=\"https://scm/user/project/raw/#SHA#/images/rel2.png\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        (str
                         "<img src=\"rel1.png\">"
                         "<img src=\"../../images/rel2.png\">")
                        fix-opts )))))

(t/deftest fix-resolves-root-relative-images-to-scm-project-root-raw-refs
  (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/root/relative/image.png\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<img src=\"/root/relative/image.png\">"
                        fix-opts )))))

(t/deftest fix-brings-in-svgs-raw-from-scm
  (t/is (= ["<img src=\"https://scm/user/project/raw/#SHA#/doc/path/rel.svg\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<img src=\"rel.svg\">"
                        fix-opts)))))

(t/deftest fix-sanitizes-svg-from-github
  (t/is (= ["<img src=\"https://github.com/user/project/raw/#SHA#/doc/path/rel.svg?sanitize=true\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<img src=\"rel.svg\">"
                        {:scm {:commit "#SHA#"
                               :url "https://github.com/user/project"}
                         :uri-map {}})))))

(t/deftest fix-brings-in-other-svg-images-as-is
  (t/is (= ["<img src=\"https://svgworld.com/abs.svg\">"]
           (fix-result
            (fixref/fix "doc/path/doc.adoc"
                        "<img src=\"https://svgworld.com/abs.svg\">"
                        fix-opts)))))

(t/deftest fix-resolves-images-to-local-simple-server-when-running-in-local-mode
  (let [scm-as-local-dir "./"]
    (t/is (= ["<img src=\"http://localhost:9090/doc/path/rel.svg\">"]
             (fix-result
              (fixref/fix "doc/path/doc.adoc"
                          "<img src=\"rel.svg\">"
                          {:scm {:commit "#SHA#"
                                 :url scm-as-local-dir}
                           :uri-map {}}))))))

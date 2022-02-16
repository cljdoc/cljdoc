(ns cljdoc.render.rich-text-test
  (:require [cljdoc.render.rich-text :as rich-text]
            [clojure.test :as t]))

(t/deftest markdown-wikilink
  (t/testing "renders as link when ref resolves"
    (t/is (= "<p><a href=\"/resolved/to/something\" data-source=\"wikilink\"><code>my.namespace.here/fn1</code></a></p>\n"
             (rich-text/markdown-to-html "[[my.namespace.here/fn1]]"
                                         {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))}))))
  (t/testing "is not rendered as link"
    (t/testing "when |text is included"
      (t/is (= "<p>[[my.namespace.here/fn1|some text here]]</p>\n"
               (rich-text/markdown-to-html "[[my.namespace.here/fn1|some text here]]"
                                           {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))}))))
    (t/testing "when wikilink rendering not enabled"
      (t/is (= "<p>[[<em>some random markdown</em>]]</p>\n"
               (rich-text/markdown-to-html "[[*some random markdown*]]"
                                           {}))))
    (t/testing "when does not resolve"
      (t/is (= "<p>[[<em>some random markdown</em>]]</p>\n"
               (rich-text/markdown-to-html "[[*some random markdown*]]"
                                           {:render-wiki-link (constantly nil)}))))
    (t/testing "when empty"
      (t/is (= "<p>[[]]</p>\n"
               (rich-text/markdown-to-html "[[]]"
                                           {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))}))))))

(t/deftest md-html-escaping
  (t/is (= "<p>&lt;h1&gt;Hello&lt;/h1&gt;</p>\n"
           (rich-text/markdown-to-html "<h1>Hello</h1>" {:escape-html? true})))
  (t/is (= "<h1>Hello</h1>\n"
           (rich-text/markdown-to-html "<h1>Hello</h1>" {:escape-html? false}))))

(t/deftest determines-doc-features
  (t/is (nil? (rich-text/determine-features [:cljdoc/markdown "== CommonMark has no optional features"])))
  (t/is (nil? (rich-text/determine-features [:cljdoc/asciidoc "= My Adoc file is short and uses no stem"])))
  (t/is (nil? (rich-text/determine-features [:cljdoc/asciidoc
                                             (str "= Stem option must be in the header which ends after the first blank line\n"
                                                  ":toc:\n"
                                                  "\n"
                                                  ":stem:\n")])))
  (t/is (nil? (rich-text/determine-features [:cljdoc/asciidoc
                                             (str "= Valid stem in the header, but header never completes\n"
                                                  ":toc:\n"
                                                  ":stem:\n")])))
  (t/is (= {:mathjax true} (rich-text/determine-features [:cljdoc/asciidoc
                                                          (str "= Valid stem\n"
                                                               ":toc:\n"
                                                               ":stem:\n"
                                                               "\n"
                                                               "== My doc starts in earnest")])))
  (t/is (= {:mathjax true} (rich-text/determine-features [:cljdoc/asciidoc
                                                          (str "= Valid stem with some opts\n"
                                                               ":toc:\n"
                                                               ":stem: with some options\n"
                                                               "\n"
                                                               "== My doc starts in earnest")]))))

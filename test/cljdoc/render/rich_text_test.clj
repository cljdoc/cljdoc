(ns cljdoc.render.rich-text-test
  (:require [cljdoc.render.rich-text :as rich-text]
            [clojure.test :as t]))

(t/deftest renders-wikilinks-from-markdown
  (t/is (= "<p><a href=\"/updated:my.namespace.here/fn1\" data-source=\"wikilink\"><code>my.namespace.here/fn1</code></a></p>\n"
           (rich-text/markdown-to-html "[[my.namespace.here/fn1]]"
                                       {:render-wiki-link (fn [ref] (str "/updated:" ref))}))))

(t/deftest determines-doc-features
  (t/is (nil? (rich-text/determine-features [:cljdoc/markdown "== CommonMark has not optional features"])))
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

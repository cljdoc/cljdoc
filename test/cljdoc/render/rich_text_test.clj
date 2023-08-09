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

(t/deftest markdown-imgref-linkref-test
  ;; we have hacked around a flexmark bug, here we test that hack.
  (t/testing "as described https://github.com/vsch/flexmark-java/issues/551"
    (t/is (= "<p><a href=\"http://www.example.com/index.html\"><img src=\"https://picsum.photos/200\" alt=\"alt text\" /></a></p>\n"
             (rich-text/markdown-to-html (str "[![alt text][img-url]][target-url]\n"
                                              "\n"
                                              "[target-url]: http://www.example.com/index.html\n"
                                              "[img-url]: https://picsum.photos/200\n")))))
  (t/testing "extract from reported https://github.com/cljdoc/cljdoc/issues/743"
    (t/is (= (str "<h1><a href=\"#mathboxcljs\" id=\"mathboxcljs\" class=\"md-anchor\">MathBox.cljs</a></h1>\n"
                  "<p>A <a href=\"https://reagent-project.github.io/\">Reagent</a> interface to the <a href=\"https://github.com/unconed/mathbox\">MathBox</a> mathematical\nvisualization library.</p>\n"
                  "<p><a href=\"https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml\"><img src=\"https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml/badge.svg?branch&#61;main\" alt=\"Build Status\" /></a>\n"
                  "<a href=\"LICENSE\"><img src=\"https://img.shields.io/badge/license-MIT-brightgreen.svg\" alt=\"License\" /></a>\n"
                  "<a href=\"https://cljdoc.org/d/org.mentat/mathbox.cljs/CURRENT\"><img src=\"https://cljdoc.org/badge/org.mentat/mathbox.cljs\" alt=\"cljdoc badge\" /></a>\n"
                  "<a href=\"https://clojars.org/org.mentat/mathbox.cljs\"><img src=\"https://img.shields.io/clojars/v/org.mentat/mathbox.cljs.svg\" alt=\"Clojars Project\" /></a>\n"
                  "<a href=\"https://discord.gg/hsRBqGEeQ4\"><img src=\"https://img.shields.io/discord/731131562002743336?style&#61;flat&amp;colorA&#61;000000&amp;colorB&#61;000000&amp;label&#61;&amp;logo&#61;discord\" alt=\"Discord Shield\" /></a></p>\n")
             (rich-text/markdown-to-html "# MathBox.cljs

A [Reagent][reagent-url] interface to the [MathBox][mathbox-url] mathematical
visualization library.

[![Build Status][build-status]][build-status-url]
[![License][license]][license-url]
[![cljdoc badge][cljdoc]][cljdoc-url]
[![Clojars Project][clojars]][clojars-url]
[![Discord Shield][discord]][discord-url]

[build-status-url]: https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml
[build-status]: https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml/badge.svg?branch=main
[cljdoc-url]: https://cljdoc.org/d/org.mentat/mathbox.cljs/CURRENT
[cljdoc]: https://cljdoc.org/badge/org.mentat/mathbox.cljs
[clojars-url]: https://clojars.org/org.mentat/mathbox.cljs
[clojars]: https://img.shields.io/clojars/v/org.mentat/mathbox.cljs.svg
[discord-url]: https://discord.gg/hsRBqGEeQ4
[discord]: https://img.shields.io/discord/731131562002743336?style=flat&colorA=000000&colorB=000000&label=&logo=discord
[license-url]: LICENSE
[license]: https://img.shields.io/badge/license-MIT-brightgreen.svg
[mathbox-url]: https://github.com/unconed/mathbox
[reagent-url]: https://reagent-project.github.io/
"))))
  (t/testing "a solo imgref is not broken by our fix"
    (t/is (= "<p><img src=\"foo.png\" alt=\"foo alt\" /></p>\n"
             (rich-text/markdown-to-html "![foo alt][foo-img-url]\n\n[foo-img-url]: foo.png")))))

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

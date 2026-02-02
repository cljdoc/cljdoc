(ns cljdoc.render.rich-text-test
  (:require [cljdoc.render.rich-text :as rich-text]
            [clojure.string :as str]
            [clojure.test :as t]
            [hickory.core :as hickory]
            [matcher-combinators.test])
  (:import (org.jsoup Jsoup)
           (org.jsoup.select NodeTraversor NodeVisitor)
           (org.jsoup.nodes TextNode)))

(set! *warn-on-reflection* true)

(defn- html->hiccup
  "Nice to verify in hiccup rather than html"
  [^String html]
  (let [doc (Jsoup/parse html)]
    (-> (.outputSettings doc)
        (.prettyPrint false))
    ;; turf extra newlines, to avoid having to deal with them test expectations
    (NodeTraversor/traverse
     (reify NodeVisitor
       (head [_ node _depth]
         (when (and (instance? TextNode node)
                    (re-matches #"\s*" (.getWholeText ^TextNode node)))
           (.remove node)))
       (tail [_ _node _depth] nil))
     doc)
    (->> doc
         .body
         .toString
         (hickory/parse-fragment)
         (map hickory/as-hiccup))))

(t/deftest markdown-wikilink
  (t/testing "renders as link when ref resolves"
    (t/is (match?
           [[:p {} [:a {:href "/resolved/to/something" :data-source "wikilink"}
                    [:code {} "my.namespace.here/fn1"]]]]
           (html->hiccup
            (rich-text/markdown-to-html
             "[[my.namespace.here/fn1]]"
             {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))})))))
  (t/testing "is not rendered as link"
    (t/testing "when |text is included"
      (t/is (match?
             [[:p {} "[[my.namespace.here/fn1|some text here]]"]]
             (html->hiccup
              (rich-text/markdown-to-html
               "[[my.namespace.here/fn1|some text here]]"
               {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))})))))
    (t/testing "when wikilink rendering not enabled"
      (t/is (match? [[:p {} "[[" [:em {} "some random markdown"] "]]"]]
                    (html->hiccup
                     (rich-text/markdown-to-html "[[*some random markdown*]]"
                                                 {})))))
    (t/testing "when does not resolve"
      (t/is (match? [[:p {} "[[" [:em {} "some random markdown"] "]]"]]
                    (html->hiccup
                     (rich-text/markdown-to-html
                      "[[*some random markdown*]]"
                      {:render-wiki-link (constantly nil)})))))
    (t/testing "when empty"
      (t/is (match? [[:p {} "[[]]"]]
                    (html->hiccup
                     (rich-text/markdown-to-html
                      "[[]]"
                      {:render-wiki-link (fn [wikilink-ref] (when (= "my.namespace.here/fn1" wikilink-ref) "/resolved/to/something"))})))))))

(defn- expected-alert-hiccup [alert-type body]
  [(apply
    conj
    [:div
     {:class (str "markdown-alert markdown-alert-" alert-type)}
     [:p {:class "markdown-alert-title"} alert-type]]
    body)])

(t/deftest github-alert-test
  ;; we implemented support for github alerts
  ;; alerts are a syntactic superset of block quote, so should render as blockquote if not an alert
  (doseq [alert-type ["important" "warning" "caution" "note" "tip"]]
    (t/testing alert-type
      (t/is (match?
             (expected-alert-hiccup alert-type
                                    [[:p {} (format "My %s text" alert-type)]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               (format "> My %s text" alert-type)]))))
            "single line in first paragraph")
      (t/is (match?
             (expected-alert-hiccup alert-type [[:p {} "para1line1\npara1line2"]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               "> para1line1"
                               "> para1line2"]))))
            "multi line in first paragraph")
      (t/is (match?
             (expected-alert-hiccup alert-type [[:p {} "para1line1"]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               ">"
                               "> para1line1"]))))
            "single line in subsequent node")
      (t/is (match?
             (expected-alert-hiccup alert-type
                                    [[:p {} "para1line1\npara1line2"]])

             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               ">"
                               "> para1line1"
                               "> para1line2"]))))
            "multi line in subsequent node")
      (t/is (match?
             (expected-alert-hiccup alert-type
                                    [[:p {} "para1line1\npara1line2"]
                                     [:p {} "para2line1\npara2line2"]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               ">"
                               "> para1line1"
                               "> para1line2"
                               ">"
                               "> para2line1"
                               "> para2line2"]))))
            "multiple subsequent paragraphs")
      (t/is (match?
             (expected-alert-hiccup alert-type
                                    [[:p {} "para1line1\npara1line2"]
                                     [:p {} "para2line1"]
                                     [:p {} "para3line1\npara3line2\npara3extra"]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [(format "> [!%s]" (str/upper-case alert-type))
                               "> para1line1"
                               "> para1line2"
                               ">"
                               "> para2line1"
                               ">"
                               "> para3line1"
                               "> para3line2"
                               "para3extra"]))))
            "first paragraph and subsequent nodes")
      (t/is (match?
             (expected-alert-hiccup alert-type
                                    [[:p {} "para1line1\npara1line2"]
                                     [:p {} "para2line1"]
                                     [:p {} "para3line1\npara3line2\npara3extra"]])
             (html->hiccup
              (rich-text/markdown-to-html
               (str/join "\n" [">"
                               ">"
                               ">"
                               (format ">    [!%s]" (str/upper-case alert-type))
                               "> para1line1"
                               "> para1line2"
                               ">"
                               ">"
                               ">"
                               ">"
                               ">"
                               ">"
                               "> para2line1"
                               ">"
                               ">"
                               ">"
                               ">"
                               "> para3line1"
                               "> para3line2"
                               "> para3extra"
                               ">"
                               ">"]))))
            "empty > lines do not affect result"))))

(t/deftest not-an-alert-test
  (t/is (match?
         [[:blockquote {}
           ;; indented md code is code block...
           [:pre {} [:code {:class "language-clojure"} "[!TIP]\n"]]
           [:p {} "Not an alert"]]]
         (html->hiccup
          (rich-text/markdown-to-html
                                        ; 12345
           (str/join "\n" [">     [!TIP]"
                           "> Not an alert"]))))
        "alert type indented more than 4 spaces")
  (t/is (match?
         [;; outer is an alert
          [:div {:class "markdown-alert markdown-alert-tip"}
           [:p {:class "markdown-alert-title"} "tip"]
           [:p {} "A tip"]
           ;; inner is a blockquote
           [:blockquote {}
            [:p {} "[!NOTE]\nnot a nested alert"]]]]
         (html->hiccup
          (rich-text/markdown-to-html
                                        ; 12345
           (str/join "\n" ["> [!TIP]"
                           "> A tip"
                           ">"
                           "> > [!NOTE]"
                           "> > not a nested alert"]))))
        "nested alerts are not a thing"))

(t/deftest markdown-imgref-linkref-test
  ;; we have hacked around a flexmark bug, here we test that hack.
  (t/testing "as described https://github.com/vsch/flexmark-java/issues/551"
    (t/is (match?
           [[:p {}
             [:a {:href "http://www.example.com/index.html"}
              [:img {:alt "alt text" :src "https://picsum.photos/200"}]]]]
           (html->hiccup
            (rich-text/markdown-to-html (str "[![alt text][img-url]][target-url]\n"
                                             "\n"
                                             "[target-url]: http://www.example.com/index.html\n"
                                             "[img-url]: https://picsum.photos/200\n"))))))
  (t/testing "extract from reported https://github.com/cljdoc/cljdoc/issues/743"
    (t/is (match?
           [[:h1 {} [:a {:href "#mathboxcljs" :id "mathboxcljs" :class "md-anchor"} "MathBox.cljs"]]
            [:p {} "A "
             [:a {:href "https://reagent-project.github.io/"} "Reagent"]
             " interface to the "
             [:a {:href "https://github.com/unconed/mathbox"} "MathBox"]
             " mathematical\nvisualization library."]
            [:p {}
             [:a {:href "https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml"}
              [:img {:alt "Build Status" :src "https://github.com/mentat-collective/mathbox.cljs/actions/workflows/kondo.yml/badge.svg?branch=main"}]]
             [:a {:href "LICENSE"}
              [:img {:alt "License" :src "https://img.shields.io/badge/license-MIT-brightgreen.svg"}]]
             [:a {:href "https://cljdoc.org/d/org.mentat/mathbox.cljs/CURRENT"}
              [:img {:alt "cljdoc badge" :src "https://cljdoc.org/badge/org.mentat/mathbox.cljs"}]]
             [:a {:href "https://clojars.org/org.mentat/mathbox.cljs"}
              [:img {:alt "Clojars Project" :src "https://img.shields.io/clojars/v/org.mentat/mathbox.cljs.svg"}]]
             [:a {:href "https://discord.gg/hsRBqGEeQ4"}
              [:img {:alt "Discord Shield" :src "https://img.shields.io/discord/731131562002743336?style=flat&colorA=000000&colorB=000000&label=&logo=discord"}]]]]
           (html->hiccup
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
")))))
  (t/testing "a solo imgref is not broken by our fix"
    (t/is (match?
           [[:p {} [:img {:src "foo.png" :alt "foo alt"}]]]
           (html->hiccup
            (rich-text/markdown-to-html "![foo alt][foo-img-url]\n\n[foo-img-url]: foo.png"))))))

(t/deftest md-html-escaping
  (t/is (match?
         [[:p {} "&lt;h1&gt;Hello&lt;/h1&gt;"]]
         (html->hiccup
          (rich-text/markdown-to-html "<h1>Hello</h1>" {:escape-html? true}))))
  (t/is (match?
         [[:h1 {} "Hello"]]
         (html->hiccup
          (rich-text/markdown-to-html "<h1>Hello</h1>" {:escape-html? false})))))

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

(t/deftest emoji-unicode-test
  (t/is (match? ["fire :fire:"]
                (html->hiccup (rich-text/render-text [:cljdoc/plaintext "fire :fire:"]))) "plaintext")
  (t/is (match? [[:div {:class "paragraph"}
                  [:p {} "fire 🔥"]]]
                (html->hiccup (rich-text/render-text [:cljdoc/asciidoc "fire :fire:"]))) "asciidoc")
  (t/is (match? [[:p {} "fire 🔥"]]
                (html->hiccup (rich-text/render-text [:cljdoc/markdown "fire :fire:"]))) "markdown"))

(t/deftest emoji-wrapped-unicode-test
  ;; github wrapped some unicode on render, let's test that we render these ok
  (t/is (match? ["o2 :o2:"]
                (html->hiccup (rich-text/render-text [:cljdoc/plaintext "o2 :o2:"]))) "plaintext")
  (t/is (match? [[:div {:class "paragraph"}
                  [:p {} "o2 🅾️"]]]
                (html->hiccup (rich-text/render-text [:cljdoc/asciidoc "o2 :o2:"]))) "asciidoc")
  (t/is (match? [[:p {} "o2 🅾️"]]
                (html->hiccup (rich-text/render-text [:cljdoc/markdown "o2 :o2:"]))) "markdown"))

(t/deftest emoji-img-test
  ;; emojis that have no unicode are rendered as images
  (t/is (match? ["atom :atom:"]
                (html->hiccup (rich-text/render-text [:cljdoc/plaintext "atom :atom:"]))) "plaintext")
  (t/is (match? [[:div
                  {:class "paragraph"}
                  [:p {} "atom "
                   [:img {:src "https://github.githubassets.com/images/icons/emoji/atom.png?v8",
                          :style "width:1rem;height:1rem;vertical-align:middle;"}]]]]
                (html->hiccup (rich-text/render-text [:cljdoc/asciidoc "atom :atom:"]))) "asciidoc")
  (t/is (match? [[:p {} "atom "
                  [:img {:src "https://github.githubassets.com/images/icons/emoji/atom.png?v8",
                         :style "width:1rem;height:1rem;vertical-align:middle;"}]]]
                (html->hiccup (rich-text/render-text [:cljdoc/markdown "atom :atom:"]))) "markdown"))

(t/deftest emoji-unrecognized-tag-test
  (t/is (match? ["nope :nope:"]
                (html->hiccup (rich-text/render-text [:cljdoc/plaintext "nope :nope:"]))) "plaintext")
  (t/is (match? [[:div
                  {:class "paragraph"}
                  [:p {} "nope :nope:"]]]
                (html->hiccup (rich-text/render-text [:cljdoc/asciidoc "nope :nope:"]))) "asciidoc")
  (t/is (match? [[:p {} "nope :nope:"]]
                (html->hiccup (rich-text/render-text [:cljdoc/markdown "nope :nope:"]))) "markdown"))

(t/deftest emojis-should-not-be-xlated-in-literal-blocks-test
  (t/is (match?
         [[:h1 {} "🔥 Title"]
          [:div {:class "paragraph"}
           [:p {} "Inline " [:code {} ":fire:"]]]
          [:div {:class "listingblock"}
           [:div {:class "content"}
            [:pre {:class "highlight"}
             [:code {} "source :fire:"]]]]
          [:div {:class "literalblock"}
           [:div {:class "content"}
            [:pre {} "Literal :fire:"]]]
          [:div {:class "literalblock"}
           [:div {:class "content"}
            [:pre {} "Another literal :fire:"]]]
          [:div {:class "literalblock"}
           [:div {:class "content"}
            [:pre {} "Indented literal :fire:"]]]]
         (html->hiccup (rich-text/render-text
                        [:cljdoc/asciidoc (str "= :fire: Title\n"
                                               "\n"
                                               "Inline `:fire:`\n"
                                               "[source]\n"
                                               "----\n"
                                               "source :fire:\n"
                                               "----\n"
                                               "\n"
                                               "[literal]\n"
                                               "Literal :fire:\n"
                                               "\n"
                                               "....\n"
                                               "Another literal :fire:\n"
                                               "....\n"
                                               "\n"
                                               " Indented literal :fire:")])))
        "asciidoc")
  (t/is (match?
         [[:h1 {} [:a {:href "#fire-title", :id "fire-title", :class "md-anchor"} "🔥 Title"]]
          [:p {} "Inline " [:code {} ":fire:"]]
          ;; TODO: hmmm.. default lang is clojure?
          [:pre {} [:code {:class "language-clojure"} "source :fire:\n"]]
          [:pre {} "pre :fire:\n"]]
         (html->hiccup (rich-text/render-text
                        [:cljdoc/markdown (str "# :fire: Title\n"
                                               "\n"
                                               "Inline `:fire:`\n"
                                               "```\n"
                                               "source :fire:\n"
                                               "```\n"
                                               "\n"
                                               "<pre>\n"
                                               "pre :fire:\n"
                                               "</pre>")])))
        "markdown"))

(t/deftest multi-emojis-test
  (t/is (match?
         [[:div {:class "paragraph"} [:p {} "Emoji examples"]]
          [:div {:class "paragraph"}
           [:p {}
            "🔥"
            [:img {:src "https://github.githubassets.com/images/icons/emoji/octocat.png?v8",
                   :style "width:1rem;height:1rem;vertical-align:middle;"}]
            "🧝‍♂️🅾️"]]
          [:div {:class "paragraph"} [:p {} "🧠octocat🧝‍♂️airplane:"]]
          [:div {:class "paragraph"} [:p {} "🧠octocat✈️"]]
          [:div {:class "paragraph"}
           [:p {}
            "🍞 "
            [:img
             {:src "https://github.githubassets.com/images/icons/emoji/bowtie.png?v8",
              :style "width:1rem;height:1rem;vertical-align:middle;"}]
            " 🐶 ♣️"]]
          [:div {:class "paragraph"}
           [:p {}
            "I like 🍪s+☕,🥞&amp;"
            [:img
             {:src "https://github.githubassets.com/images/icons/emoji/fishsticks.png?v8",
              :style "width:1rem;height:1rem;vertical-align:middle;"}]
            "!"]]]
         (html->hiccup (rich-text/render-text
                        [:cljdoc/asciidoc (str "Emoji examples\n\n"
                                               ":fire::octocat::elf_man::o2:\n\n"
                                               ":brain:octocat:elf_man:airplane:\n\n"
                                               ":brain:octocat:airplane:\n\n"
                                               ;; use + to passthrough to avoid being interpreted as custom doc attribute
                                               "+:bread:+ :bowtie: :dog: :clubs:\n\n"
                                               "I like :cookie:s+:coffee:,:pancakes:&:fishsticks:!\n\n")])))
        "asciidoc")
  (t/is (match?
          [[:p {} "Emoji examples"]
           [:p {}
            "🔥"
            [:img {:src "https://github.githubassets.com/images/icons/emoji/octocat.png?v8",
                   :style "width:1rem;height:1rem;vertical-align:middle;"}]
            "🧝‍♂️🅾️"]
           [:p {} "🧠octocat🧝‍♂️airplane:"]
           [:p {} "🧠octocat✈️"]
           [:p {}
            "🍞 "
            [:img
             {:src "https://github.githubassets.com/images/icons/emoji/bowtie.png?v8",
              :style "width:1rem;height:1rem;vertical-align:middle;"}]
            " 🐶 ♣️"]
           [:p {}
            "I like 🍪s+☕,🥞&amp;"
            [:img
             {:src "https://github.githubassets.com/images/icons/emoji/fishsticks.png?v8",
              :style "width:1rem;height:1rem;vertical-align:middle;"}]
            "!"]] 
           (html->hiccup (rich-text/render-text
                          [:cljdoc/markdown (str "Emoji examples\n\n"
                                               ":fire::octocat::elf_man::o2:\n\n"
                                               ":brain:octocat:elf_man:airplane:\n\n"
                                               ":brain:octocat:airplane:\n\n"
                                               ":bread: :bowtie: :dog: :clubs:\n\n"
                                               "I like :cookie:s+:coffee:,:pancakes:&:fishsticks:!\n\n")])))
          "markdown"))


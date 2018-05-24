(ns cljdoc.renderers.common
  (:require [hiccup.core :as hiccup]
            [hiccup.page]))

(defn github-url [type]
  (let [base "https://github.com/martinklepsch/cljdoc"]
    (case type
      :home               base
      :issues             (str base "/issues")
      :rationale          (str base "#rationale")
      :contributors       (str base "/graphs/contributors")
      :userguide/articles (str base "/blob/master/doc/userguide/articles.md")
      :userguide/scm-faq  (str base "/blob/master/doc/userguide/faq.md#how-do-i-set-scm-info-for-my-project"))))

(defn page [opts contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {}
                [:head
                 [:title (:title opts)]
                 [:meta {:charset "utf-8"}]
                 (hiccup.page/include-css
                   "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"
                   "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/styles/github-gist.min.css"
                   "/cljdoc.css")]
                [:div.sans-serif
                 contents]
                (hiccup.page/include-js
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js"
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure.min.js"
                  "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure-repl.min.js"
                  "/cljdoc.js")
                [:script "hljs.initHighlightingOnLoad();"]]))


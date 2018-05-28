(ns cljdoc.render.layout
  "Components to layout cljdoc pages"
  (:require [hiccup.core :as hiccup]
            [hiccup.page]))

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

(defn sidebar-title [title]
  [:h4.ttu.f7.fw5.tracked.gray title])

(def TOP-BAR-HEIGHT "57px")

(defn sidebar [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10
   {:style {:top TOP-BAR-HEIGHT}} ; CSS HACK
   contents])

(defn sidebar-two [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10.sidebar-scroll-view
   {:style {:top TOP-BAR-HEIGHT :left "16rem"}} ; CSS HACK
   contents])

(defn main-container [{:keys [offset]} & content]
   [:div.absolute.bottom-0.right-0
    {:style {:left offset :top TOP-BAR-HEIGHT}}
    (into [:div.absolute.top-0.bottom-0.left-0.right-0.overflow-y-scroll.ph4-ns.ph2.main-scroll-view]
          content)])

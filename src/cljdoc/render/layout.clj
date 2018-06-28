(ns cljdoc.render.layout
  "Components to layout cljdoc pages"
  (:require [cljdoc.server.routes :as routes]
            [cljdoc.util :as util]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]
            [hiccup.page]))

(defn highlight-js []
  [:div
   (hiccup.page/include-js
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js"
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure.min.js"
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure-repl.min.js")
   [:script
    (->> ["hljs.initHighlightingOnLoad();"
          "hljs.registerLanguage('cljs', function (hljs) { return hljs.getLanguage('clj') });"]
         (string/join "\n")
         (hiccup/raw))]])

(defn page [opts contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {}
                [:head
                 [:title (:title opts)]
                 [:meta {:charset "utf-8"}]
                 (hiccup.page/include-css
                   "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/styles/github-gist.min.css"
                   "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"
                   "/cljdoc.css")]
                [:div.sans-serif
                 contents]
                (hiccup.page/include-js "/cljdoc.js")
                (highlight-js)]))

(defn sidebar-title [title]
  [:h4.ttu.f7.fw5.mt1.mb2.tracked.gray title])

(def TOP-BAR-HEIGHT "57px")

(defn sidebar [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10
   {:class "js--sidebar"
    :style {:top TOP-BAR-HEIGHT}} ; CSS HACK
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

(defn top-bar [cache-id scm-url]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a.dib.v-mid.link.dim.black.b.f6.mr3 {:href (routes/url-for :artifact/version :path-params cache-id)}
    (util/clojars-id cache-id)]
   [:a.dib.v-mid.link.dim.gray.f6.mr3
    {:href (routes/url-for :artifact/index :path-params cache-id)}
    (:version cache-id)]
   [:a {:href "/"}
    [:span.link.dib.v-mid.mr3.pv1.ph2.ba.hover-blue.br1.ttu.fw5.f7.silver.tracked "cljdoc Alpha"]]
   [:a.silver.link.hover-blue.ttu.fw5.f7.tracked.pv1
    {:href (util/github-url :issues)}
    "Have Feedback?"]
   [:div.tr
    {:style {:flex-grow 1}}
    [:form.dib.mr3 {:action "/api/request-build2" :method "POST"}
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "project" :name "project" :value (str (:group-id cache-id) "/" (:artifact-id cache-id))}]
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "version" :name "version" :value (:version cache-id)}]
     [:input.f7.white.hover-near-white.outline-0.bn.bg-white {:type "submit" :value "rebuild"}]]
    (if scm-url
      [:a.link.dim.gray.f6.tr
       {:href scm-url}
       [:img.v-mid.mr2 {:src "https://icon.now.sh/github"}]
       [:span.dib (util/gh-coordinate scm-url)]]
      [:a.f6.link.blue {:href (util/github-url :userguide/scm-faq)} "SCM info missing"])]])

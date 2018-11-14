(ns cljdoc.render.layout
  "Components to layout cljdoc pages"
  (:require [cljdoc.server.routes :as routes]
            [cljdoc.config :as config]
            [cljdoc.util :as util]
            [clojure.string :as string]
            [hiccup2.core :as hiccup]
            [hiccup.page]))

(defn highlight-js-customization []
  [:script
   (->> ["hljs.initHighlightingOnLoad();"
         "hljs.registerLanguage('cljs', function (hljs) { return hljs.getLanguage('clj') });"]
        (string/join "\n")
        (hiccup/raw))])

(defn highlight-js []
  [:div
   (hiccup.page/include-js
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/highlight.min.js"
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure.min.js"
    "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/languages/clojure-repl.min.js")
   (highlight-js-customization)])

(defn description
  "Return a string to be used as description meta tag for a given project's documentation pages."
  [{:keys [group-id artifact-id version] :as cache-id}]
  (format "Documentation for %s v%s on cljdoc, a website that builds and hosts documentation for Clojure/Script libraries."
          (util/clojars-id cache-id) version))

(defn no-js-warning []
  [:div.fixed.left-0.right-0.bottom-0.bg-washed-red.code.b--light-red.bw3.ba.dn
   {:id "no-js-warning"}
   [:script
    (hiccup/raw "fetch(\"/js/index.js\").then(e => e.status === 200 ? null : document.getElementById('no-js-warning').classList.remove('dn'))")]
   [:p.ph4 "Could not find JavaScript assets, please refer to " [:a.fw7.link {:href (util/github-url :running-locally)} "the documentation"] " for how to build JS assets."]])

(defn page [opts contents]
  (hiccup/html {:mode :html}
               (hiccup.page/doctype :html5)
               [:html {}
                [:head
                 [:title (:title opts)]
                 [:meta {:content (:description opts) :name "description"}]

                 ;; Google / Search Engine Tags
                 [:meta {:content (:title opts) :itemprop "name"}]
                 [:meta {:content (:description opts) :itemprop "description"}]
                 [:meta {:content "https://cljdoc.org/cljdoc-logo-beta-square.png" :itemprop "image"}]

                 ;; OpenGraph Meta Tags (should work for Twitter/Facebook)
                 ;; TODO [:meta {:content "" :property "og:url"}]
                 [:meta {:content "website" :property "og:type"}]
                 [:meta {:content (:title opts) :property "og:title"}]
                 [:meta {:content (:description opts) :property "og:description"}]
                 [:meta {:content "https://cljdoc.org/cljdoc-logo-beta-square.png" :property "og:image"}]

                 ;; Canonical URL
                 (when-let [url (:canonical-url opts)]
                   (assert (.startsWith url "/"))
                   [:link {:rel "canonical" :href (str "https://cljdoc.org" url)}]); TODO read domain from config

                 (when (:responsive? opts)
                   [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}])
                 [:meta {:charset "utf-8"}]
                 (hiccup.page/include-css
                   "https://cdn.jsdelivr.net/gh/highlightjs/cdn-release@9.12.0/build/styles/github-gist.min.css"
                   "https://unpkg.com/tachyons@4.9.0/css/tachyons.min.css"
                   "/cljdoc.css")]
                [:body
                 [:div.sans-serif
                  contents]
                 (when (not= :prod (config/profile))
                   (no-js-warning))
                 [:div#cljdoc-switcher]
                 [:script {:src "/js/index.js"}]
                 (highlight-js)]]))

(defn sidebar-title [title]
  [:h4.ttu.f7.fw5.mt1.mb2.tracked.gray title])

(def TOP-BAR-HEIGHT 57)

(defn sidebar [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10
   {:class "js--sidebar"
    :style {:top (str TOP-BAR-HEIGHT "px")}} ; CSS HACK
   contents])

(defn sidebar-two [& contents]
  [:div.absolute.w5.bottom-0.left-0.pa3.pa4-ns.overflow-scroll.br.b--black-10.sidebar-scroll-view
   {:style {:top (str TOP-BAR-HEIGHT "px") :left "16rem"}} ; CSS HACK
   contents])

(defn meta-info-dialog []
  [:div#js--meta-dialog.ma3.pa3.ba.br3.b--blue.bw2.w-20.fixed.right-0.bottom-0.bg-white.dn
   [:p.ma0
    [:b "cljdoc"]
    " is a website building & hosting documentation for Clojure/Script libraries"]
   (into [:div.mv3]
         (map (fn [[description link]]
                [:a.db.link.black.mv1.pv3.tc.br2.pointer
                 {:href link, :style {:background-color "#ECF2FB"}}
                 description])
              [["Keyboard shortcuts"  (routes/url-for :shortcuts)]
               ["Report a problem"    (util/github-url :issues)]
               ;; ["Recent improvements" "#"] TODO add link once it exists
               ["cljdoc on GitHub"    (util/github-url :home)]]))
   [:a#js--meta-close.link.black.fr.pointer
    "× close"]])

(defn main-container [{:keys [offset extra-height]} & content]
   [:div.absolute.bottom-0.right-0
    {:style {:left offset
             :top (str (+ TOP-BAR-HEIGHT (or extra-height 0)) "px")}}
    (into [:div.absolute.top-0.bottom-0.left-0.right-0.overflow-y-scroll.ph4-ns.ph2.main-scroll-view
           [:img#js--meta-icon.ma3.fixed.right-0.bottom-0.bg-white.dn.db-ns.pointer
            {:src "https://icon.now.sh/explore/48/357edd"}]
           (meta-info-dialog)]
          content)])

(defn top-bar-generic []
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a {:href "/"}
    [:span.link.dib.v-mid.mr3.pv1.ph2.ba.hover-blue.br1.ttu.fw5.f7.silver.tracked "cljdoc Beta"]]
   [:a.silver.link.hover-blue.ttu.fw5.f7.tracked.pv1
    {:href (util/github-url :issues)}
    "Have Feedback?"]])

(defn top-bar [cache-id scm-url]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a.dib.v-mid.link.dim.black.b.f6.mr3 {:href (routes/url-for :artifact/version :path-params cache-id)}
    (util/clojars-id cache-id)]
   [:a.dib.v-mid.link.dim.gray.f6.mr3
    {:href (routes/url-for :artifact/index :path-params cache-id)}
    (:version cache-id)]
   [:a.dn.dib-ns {:href "/"}
    [:span.link.dib.v-mid.mr3.pv1.ph2.ba.hover-blue.br1.ttu.fw5.f7.silver.tracked "cljdoc Beta"]]
   [:a.dn.dib-ns.silver.link.hover-blue.ttu.fw5.f7.tracked.pv1
    {:href (util/github-url :issues)}
    "Have Feedback?"]
   [:div.tr
    {:style {:flex-grow 1}}
    [:form.dn.dib-ns.mr3 {:action "/api/request-build2" :method "POST"}
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "project" :name "project" :value (str (:group-id cache-id) "/" (:artifact-id cache-id))}]
     [:input.pa2.mr2.br2.ba.outline-0.blue {:type "hidden" :id "version" :name "version" :value (:version cache-id)}]
     [:input.f7.white.hover-near-white.outline-0.bn.bg-white {:type "submit" :value "rebuild"}]]
    (if scm-url
      [:a.link.dim.gray.f6.tr
       {:href scm-url}
       [:img.v-mid.mr2 {:src (str "https://icon.now.sh/" (name (util/scm-provider scm-url)))}]
       [:span.dib (util/scm-coordinate scm-url)]]
      [:a.f6.link.blue {:href (util/github-url :userguide/scm-faq)} "SCM info missing"])]])

(defn upgrade-notice [{:keys [version] :as version-map}]
  [:a.link {:href (routes/url-for :artifact/version :path-params version-map)}
   [:div.bg-washed-yellow.pa2.f7.mb4.dark-gray.lh-title
    "A newer version " [:span.blue "(" version ")"] " for this library is available"]])

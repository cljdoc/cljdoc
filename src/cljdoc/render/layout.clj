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
                 [:meta {:charset "utf-8"}]
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

                 [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
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

(defn top-bar-generic []
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center
   [:a {:href "/"}
    [:span.link.dib.v-mid.mr3.pv1.ph2.ba.hover-blue.br1.ttu.fw5.f7.silver.tracked "cljdoc Beta"]]
   [:a.silver.link.hover-blue.ttu.fw5.f7.tracked.pv1
    {:href (util/github-url :issues)}
    "Have Feedback?"]])

(defn top-bar [cache-id scm-url]
  [:nav.pv2.ph3.pv3-ns.ph4-ns.bb.b--black-10.flex.items-center.bg-white
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

;; Responsive Layout -----------------------------------------------------------

(def r-main-container
  "Everything that's rendered on cljdoc goes into this container.

  On desktop screens it fills the viewport forcing child nodes
  to scroll. On smaller screens it grows with the content allowing
  Safari to properly condense the URL bar when scrolling.

  In contrast if the container was fixed on mobile as well Safari
  would perceive the scroll events as if the user scrolled in a
  smaller portion of the UI.

  While `height: 100vh` would also work on desktop screens there are some
  known issues around [using viewport height units on mobile](https://nicolas-hoizey.com/2015/02/viewport-height-is-taller-than-the-visible-part-of-the-document-in-some-mobile-browsers.html)."
  :div.flex.flex-column.fixed-ns.bottom-0.left-0.right-0.top-0)

(def r-sidebar-container
  :nav.js--main-sidebar.w5.pa3.pa4-ns.br.b--black-10.db-ns.dn.overflow-y-scroll.border-box.flex-shrink-0)

(def r-api-sidebar-container
  :div.js--namespace-contents-scroll-view.w5.pa3.pa4-ns.br.b--black-10.db-ns.dn.overflow-y-scroll.flex-shrink-0)

(def r-content-container
  :div.js--main-scroll-view.ph4-ns.ph3.flex-grow-1.overflow-y-scroll)

(def r-top-bar-container
  "Additional wrapping to make the top bar responsive

  On small screens we render the top bar and mobile navigation as a
  `fixed` div so that the main container can be scrolled without the
  navigation being scrolled out of view.

  On regular sized screens we use the default positioning setting of
  `static` so that the top bar is rendered as a row of the main
  container.

  The z-index `z3` setting is necessary to ensure `fixed` elements
  appear above other elements"
  :div.fixed.top-0.left-0.right-0.static-ns.flex-shrink-0.z3)

(def mobile-nav-spacer
  "Spacer so fixed navigation elements don't overlap content
  This is only needed on small screens so `dn-ns` hides it
  on non-small screens

  The height has been found by trial and error."
  [:div.bg-blue.dn-ns.pt4.tc
   {:style {:height "5.2rem"}}
   ;; TODO render different fun messages for each request
   [:span.b.white.mt3 "Liking cljdoc? Tell your friends :D"]])

(defn layout
  [{:keys [top-bar
           main-sidebar-contents
           vars-sidebar-contents
           content]}]
  [r-main-container
   [r-top-bar-container
    top-bar
    [:div#js--mobile-nav.db.dn-ns]]
   mobile-nav-spacer
   [:div.flex.flex-row
    (into [r-sidebar-container] main-sidebar-contents)
    (when (seq vars-sidebar-contents)
      (into [r-api-sidebar-container] vars-sidebar-contents))
    ;; (when doc-html doc-nav)
    [r-content-container content]]])

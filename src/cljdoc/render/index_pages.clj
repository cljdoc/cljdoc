(ns cljdoc.render.index-pages
  "Hiccup rendering functions for index pages.

  These pages are used when switching between versions or
  browsing all artifacts under a specific group-id."
  (:require [cljdoc.util :as util]
            [cljdoc.render.layout :as layout]
            [cljdoc.server.routes :as routes]
            [clojure.spec.alpha :as spec]
            [version-clj.core :as v]))

(spec/fdef sort-by-version
  :args (spec/cat :version-entities (spec/coll-of :cljdoc.spec/version-entity)))

(defn sort-by-version [version-entities]
  (sort-by :version #(- (v/version-compare %1 %2)) version-entities))

(spec/fdef render
  :args (spec/cat :artifact-entity :cljdoc.spec/artifact-entity
                  :versions (spec/coll-of :cljdoc.spec/version-entity)))

(defn artifact-index
  [artifact-entity versions]
  (let [matching? #(= (:artifact-id artifact-entity) (:artifact-id %))
        matching  (filter matching? versions)
        others    (group-by :artifact-id (remove matching? versions))
        btn-link :a.dib.bg-blue.white.ph3.pv2.br1.no-underline.f5.fw5
        big-btn-link :a.db.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div
          (layout/top-bar-generic)
          [:div.pa4-ns.pa2
           [:h1 (util/clojars-id artifact-entity)]
           (if (empty? matching)
             [:div
              [:p "We currently don't have documentation built for " (util/clojars-id artifact-entity)]
              [:p.mt4
               [btn-link
                {:href (routes/url-for :artifact/version :path-params (assoc artifact-entity :version "CURRENT"))}
                "Go to the latest version of this artefact →"]]]
             [:div
              [:span.db "Known versions on cljdoc:"]
              [:ol.list.pl0.pv3
               (for [v (sort-by-version matching)]
                 [:li.dib.mr3.mb3
                  [big-btn-link
                   {:href (routes/url-for :artifact/version :path-params v)}
                   (:version v)]])]])
           (when (seq others)
             [:div
              [:h3 "Other artifacts under the " (:group-id artifact-entity) " group"]
              [:ol.list.pl0.pv3
               (for [[artifact-id version-entities] (sort-by first others)
                     :let [latest (first (sort-by-version version-entities))
                           a-text (str (:group-id latest) "/" (:artifact-id latest))]]
                 [:li.dib.mr3.mb3
                  [big-btn-link
                   {:href (routes/url-for :artifact/index :path-params latest)}
                   a-text]])]])]]
         (layout/page {:title (str (util/clojars-id artifact-entity) " — cljdoc")
                       :description (layout/description artifact-entity)}))))

(spec/fdef group-index
  :args (spec/cat :group-entity :cljdoc.spec/group-entity
                  :versions (spec/coll-of :cljdoc.spec/version-entity)))

(defn group-index
  [group-entity versions]
  (let [group-id (:group-id group-entity)
        big-btn-link :a.db.link.blue.ph3.pv2.bg-lightest-blue.hover-dark-blue.br2]
    (->> [:div
          (layout/top-bar-generic)
          [:div.pa4-ns.pa2
           [:h1 group-id]
           (if (empty? versions)
             [:span.db "There have not been any documentation builds for artifacts under this group, to trigger a build please go the page of a specific artefact."]
             [:div
              [:span.db "Known artifacts and versions under the group " group-id]
              [:ol.list.pl0.pv3.nl2.nr2.cf
               (for [[a-id versions] (->> (group-by :artifact-id versions)
                                          (sort-by first))
                     :let [latest (first (sort-by-version versions))]]
                 [:div.w-third-ns.fl-ns.pa2
                  [:div.ba.br2.b--blue
                   [:a.link.blue.pa3.db
                    {:href (routes/url-for :artifact/version :path-params latest)}
                    [:h3.ma0
                     (format "%s/%s %s" group-id a-id (:version latest))
                     [:img.ml1 {:src "https://icon.now.sh/chevron/right"}]
                     ]]
                   [:a.link.black.f6.ph3.pv2.bt.b--blue.db.o-60
                    {:href (routes/url-for :artifact/index :path-params latest)}
                    "more versions"]]])]])]]
         (layout/page {:title (str group-id " — cljdoc")
                       :description (format "All artifacts under the group-id %s for which there is documenation on cljdoc"
                                            group-id)}))))

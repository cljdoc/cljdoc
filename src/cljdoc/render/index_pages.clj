(ns cljdoc.render.index-pages
  "Hiccup rendering functions for index pages.

  These pages are used when switching between versions or
  browsing all artifacts under a specific group-id."
  (:require [cljdoc.util :as util]
            [cljdoc.render.layout :as layout]
            [cljdoc.server.routes :as routes]
            [clojure.spec.alpha :as spec]
            [version-clj.core :as v]))

(spec/fdef artifact-index
  :args (spec/cat :artifact-entity :cljdoc.spec/artifact-entity
                  :versions (spec/coll-of :cljdoc.spec/version-entity)))

(defn artifact-index
  [{:keys [group-id artifact-id] :as artifact-entity} versions-tree]
  (let [matching  (get-in versions-tree [group-id artifact-id])
        others    (-> (get-in versions-tree [group-id])
                      (dissoc artifact-id))
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
               (for [v matching]
                 [:li.dib.mr3.mb3
                  [big-btn-link
                   {:href (routes/url-for :artifact/version :path-params {:group-id group-id :artifact-id artifact-id :version v})}
                   v]])]])
           (when (seq others)
             [:div
              [:h3 "Other artifacts under the " group-id " group"]
              [:ol.list.pl0.pv3
               (for [[artifact-id versions] others
                     :let [latest (first versions)
                           a-text (str group-id "/" artifact-id)]]
                 [:li.dib.mr3.mb3
                  [big-btn-link
                   {:href (routes/url-for :artifact/index :path-params {:group-id group-id :artifact-id artifact-id :version latest})}
                   a-text]])]])]]
         (layout/page {:title (str (util/clojars-id artifact-entity) " — cljdoc")
                       :description (layout/description artifact-entity)}))))

(defn- artifact-grid-cell [artifact-entity]
  [:div.w-third-ns.fl-ns.pa2
   [:div.ba.br2.b--blue
    [:a.link.blue.pa3.db
     {:href (routes/url-for :artifact/version :path-params artifact-entity)}
     [:h3.ma0
      (format "%s/%s %s" (:group-id artifact-entity) (:artifact-id artifact-entity) (:version artifact-entity))
      [:img.ml1 {:src "https://microicon-clone.vercel.app/chevron/right"}]]]
    [:a.link.black.f6.ph3.pv2.bt.b--blue.db.o-60
     {:href (routes/url-for :artifact/index :path-params artifact-entity)}
     "more versions"]]])

(spec/fdef group-index
  :args (spec/cat :group-entity :cljdoc.spec/group-entity
                  :versions (spec/coll-of :cljdoc.spec/version-entity)))

(defn group-index
  [group-entity versions-tree]
  (let [group-id (:group-id group-entity)]
    (->> [:div
          (layout/top-bar-generic)
          [:div.pa4-ns.pa2
           [:h1 group-id]
           (if (empty? versions-tree)
             [:span.db "There have not been any documentation builds for artifacts under this group, to trigger a build please go the page of a specific artefact."]
             [:div
              [:span.db "Known artifacts and versions under the group " group-id]
              [:ol.list.pl0.pv3.nl2.nr2.cf
               (for [[a-id versions] (get versions-tree group-id)
                     :let [latest-version (first versions)]]
                 (artifact-grid-cell {:group-id group-id :artifact-id a-id :version latest-version}))]])]]
         (layout/page {:title (str group-id " — cljdoc")
                       :description (format "All artifacts under the group-id %s for which there is documenation on cljdoc"
                                            group-id)}))))

(spec/fdef full-index
  :args (spec/cat :versions (spec/coll-of :cljdoc.spec/version-entity)))

(defn full-index
  [versions-tree]
  (->> [:div
        (layout/top-bar-generic)
        [:div.pa4-ns.pa2
         [:div#js--cljdoc-navigator]
         [:h1.mt5 "All documented artifacts on cljdoc:"]
         (for [[group-id groups-artifact-id->versions] versions-tree]
           [:div.cf
            [:h2 group-id [:span.gray.fw3.ml3.f5 "Group ID"]]
            [:div.nl2.nr2
             (for [[a-id versions-for-artifact] groups-artifact-id->versions
                   :let [latest-version (first versions-for-artifact)]]
               (artifact-grid-cell {:group-id group-id :artifact-id a-id :version latest-version}))]])]]
       (layout/page {:title "all documented artifacts — cljdoc"})))

(defn sort-by-version [version-entities]
  (sort-by :version #(- (v/version-compare %1 %2)) version-entities))

(spec/fdef sort-by-version
           :args (spec/cat :version-entities (spec/coll-of :cljdoc.spec/version-entity)))

(defn versions-tree
  "Make the versions seq into group -> artifact -> list of versions (latest first)"
  [versions]
  (into (sorted-map)
        (for [[group-id versions-for-group] (group-by :group-id versions)]
          [group-id
           (into (sorted-map)
                 (for [[a-id versions-for-artifact] (group-by :artifact-id versions-for-group)]
                   [a-id (->>  versions-for-artifact sort-by-version (map :version))]))])))

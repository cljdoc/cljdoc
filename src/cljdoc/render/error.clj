(ns cljdoc.render.error
  (:require [cljdoc.render.layout :as layout]
            [cljdoc.render.search :as search]))

(defn- not-found-404 [static-resources {:keys [title detail]}]
  (->> [:div
        (layout/top-bar-generic)
        [:div.pt4
         [:div.mt5-ns.mt6-l.mw7.center.pa4.pa0-l
          [:h1.f2.f1-ns.b ":("]
          [:h2.f3.f2-ns.ttu (str  "404 - " title)]
          (when detail
            [:f4.f3-ns detail])
          [:p.lh-copy.i "What library are you looking for? Find it here 👇"]
          (search/search-form)]]]
       (layout/page {:static-resources static-resources
                     :title (str "cljdoc - " title)
                     :title-attributes {:data-error "404"}})
       (str)))

(defn- inline-code [text]
  [:code.gray text])

(defn not-found-page [static-resources]
  (not-found-404 static-resources {:title "Page not found"}))

(defn not-found-artifact [static-resources {:keys [group-id artifact-id version]}]
  (not-found-404 static-resources {:title "Library not found"
                                   :detail [:span
                                            "Could not find " (inline-code (str group-id "/" artifact-id))
                                            (when version (list " version " (inline-code version)))
                                            " in any maven repository"]}))

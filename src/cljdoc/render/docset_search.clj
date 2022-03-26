(ns cljdoc.render.docset-search
  (:require
   [cljdoc.server.routes :as routes]))

(defn sidebar
  [version-entity]
  [:form.black-80
   [:div.measure
    [:label.f6.b.db.mb2
     {:for "cljdoc-docset-search"}
     "Search"]
    [:input#cljdoc-docset-search-input.input-reset.ba.b--black-20.pa2.mb2.db.w-100
     {:name "cljdoc-docset-search"
      :type "text"
      :aria-describedby "cljdoc-docset-search-desc"
      :data-docset-index-url (routes/url-for :api/docset
                                             :params
                                             version-entity)}]
    [:small#cljdoc-docset-search-desc.f6.black-60.db.mb2
     "Search documents, namespaces, vars, macros, protocols, and more."]]])

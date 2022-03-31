(ns cljdoc.render.searchset-search
  (:require
   [cljdoc.server.routes :as routes]))

(defn sidebar
  [version-entity]
  [:form.black-80
   [:div.measure
    [:label.f6.b.db.mb2
     {:for "cljdoc-searchset-search"}
     "Search"]
    [:input#cljdoc-searchset-search-input.input-reset.ba.b--black-20.pa2.mb2.db.w-100
     {:name "cljdoc-searchset-search"
      :type "text"
      :aria-describedby "cljdoc-searchset-search-desc"
      :data-searchset-index-url (routes/url-for :api/searchset
                                                :params
                                                version-entity)}]
    [:small#cljdoc-searchset-search-desc.f6.black-60.db.mb2
     "Search documents, namespaces, vars, macros, protocols, and more."]]])

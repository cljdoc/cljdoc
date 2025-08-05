(ns cljdoc.client.docstring-toggler
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.page :as page]))

(warn-on-lazy-reusage!)

(defn- init-toggle-docstring-raw []
  (let [toggles (dom/query-all ".js--toggle-raw")
        add-toggle-handlers (fn []
                              (doseq [el toggles]
                                (.addEventListener el "click"
                                                   (fn []
                                                     (let [parent (.-parentElement el)
                                                           markdowns (dom/query-all ".markdown" parent)
                                                           raws (dom/query-all ".raw" parent)]
                                                       (doseq [[ndx markdown] (map-indexed vector markdowns)]
                                                         (let [raw (when raws (get raws ndx))
                                                               hide "dn"]
                                                           (if (dom/has-class? markdown hide)
                                                             (do
                                                               (dom/remove-class markdown hide)
                                                               (when raw (dom/add-class raw hide))
                                                               (set! (.-innerText el) "raw docstring"))
                                                             (do
                                                               (dom/add-class markdown hide)
                                                               (when raw (dom/remove-class raw hide))
                                                               (set! (.-innerText el) "formatted docstring"))))))))))]
    (add-toggle-handlers)))

(defn init []
  (when (or (page/is-namespace-overview)
            (page/is-namespace)
            (page/is-namespace-offline))
    (init-toggle-docstring-raw)))

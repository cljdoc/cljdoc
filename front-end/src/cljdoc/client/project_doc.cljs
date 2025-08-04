(ns cljdoc.client.project-doc
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.page :as page]))

(defn- toggle-meta-dialog []
  (when (dom/query ".js--main-scroll-view")
    (let [meta-icon (dom/query "[data-id='cljdoc-js--meta-icon']")
          meta-dialog (dom/query "[data-id='cljdoc-js--meta-dialog']")
          meta-close (dom/query "[data-id='cljdoc-js--meta-close']")
          display "db-ns"
          hide "dn"]
      (when meta-icon
        (.addEventListener meta-icon "click"
                           (fn []
                             (dom/replace-class meta-icon display hide)
                             (when meta-dialog
                               (dom/replace-class meta-dialog hide display)))))
      (when meta-close
        (.addEventListener meta-close "click"
                           (fn []
                             (dom/replace-class meta-dialog display hide)
                             (when meta-icon
                               (dom/replace-class meta-icon hide display))))))))

(defn- toggle-articles-tip []
  (let [tip-toggler (dom/query "[data-id='cljdoc-js--articles-tip-toggler']")
        tip (dom/query "[data-id='cljdoc-js--articles-tip']")]
    (when (and tip-toggler tip)
      (.addEventListener tip-toggler "click"
                         (fn [] (dom/toggle-class tip "dn"))))))

(defn- add-next-prev-key-for-articles []
  (let [prev-link (dom/query "a[data-id='cljdoc-prev-article-page-link']")
        next-link (dom/query "a[data-id='cljdoc-next-article-page-link']")]
    (when (or prev-link next-link)
      (.addEventListener js/document "keydown"
                         (fn [e]
                           (let [code (.-code e)]
                             (when (and (= "ArrowLeft" code) prev-link)
                               (set! js/document.location.href (.-href prev-link)))
                             (when (and (= "ArrowRight" code) next-link)
                               (set! js/document.location.href (.-href next-link)))))))))

(defn init []
  (when (page/is-project-doc)
    (toggle-meta-dialog)
    (toggle-articles-tip)
    (add-next-prev-key-for-articles)))

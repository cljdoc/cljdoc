(ns cljdoc.client.page
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]))

(defn is-namespace-overview []
  (boolean (dom/query ".ns-overview-page")))

(defn is-namespace []
  (boolean (dom/query ".ns-page")))

(defn is-namespace-offline []
  (boolean (dom/query ".ns-offline-page")))

(defn is-project-doc []
  (lib/coords-from-current-loc))

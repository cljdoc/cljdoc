(ns cljdoc.client.page
  (:require [cljdoc.client.dom :as dom]
            [cljdoc.client.library :as lib]))

(defn is-namespace-overview []
  (boolean (dom/query-doc ".ns-overview-page")))

(defn is-namespace []
  (boolean (dom/query-doc ".ns-page")))

(defn is-namespace-offline []
  (boolean (dom/query-doc ".ns-offline-page")))

(defn is-project-doc []
  (lib/coords-from-current-loc))

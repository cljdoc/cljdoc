(ns cljdoc.client.lib-search
  "Support for searching for a library"
  (:require ["preact" :refer [h render]]
            ["preact/hooks" :refer [useState]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.flow :as flow]
            [cljdoc.client.library :as lib]
            #_:clj-kondo/ignore ;; used in #jsx as tag
            [cljdoc.client.list-search :refer [ListSearch]]
            [clojure.string :as str]))

(defn- clean-search-str [s]
  (str/replace s #"[{}[]\"]" ""))

(defn- load-results [q call-back]
  (let [url (str "/api/search?q=" q)]
    (-> (js/fetch url)
        (.then (fn [response] (.json response)))
        (.then (fn [json] (.-results json)))
        (.then (fn [results] (call-back results))))))

(def ^:private load-results-debounced (flow/debounced 300 load-results))

(defn- RowView [{:keys [result isSelected]}]
  (let [uri (lib/docs-path result)
        rowClass (if isSelected
                   "pa3 bb b--light-gray bg-light-blue"
                   "pa3 bb b--light-gray")]
    #jsx [:<>
          [:a {:class "no-underline black" :href uri}
           [:div {:class rowClass}
            [:h4 {:class "dib ma0"}
             (lib/project result)
             [:span {:class "ml2 gray normal"}
              (:version result)]]
            [:a {:class "link blue ml2" :href uri}
             "view docs"]
            [:div {:class "gray f6"} (:blurb result)]
            [:div {:class "gray i f7"} (:origin result)]]]]))

(defn LibSearch [{:keys [initialValue]}]
  (let [[results set-results!] (useState [])]
    #jsx [:<>
          [:ListSearch {:initialValue initialValue
                        :place-holder-text "Jump to docs..."
                        :results results
                        :rowView RowView
                        :resultsFetcher (fn [q] (let [q (clean-search-str q)]
                                                  (load-results-debounced q set-results!)))
                        :onActivateItem (fn [item] (.open js/window (lib/docs-path item) "_self"))}]]))

(defn init []
  (let [search-node (dom/query "[data-id='cljdoc-search']")]
    (when (and search-node (.-dataset search-node))
      (render (h LibSearch {:initialValue (-> search-node .-dataset .-initialValue)})
              search-node))))

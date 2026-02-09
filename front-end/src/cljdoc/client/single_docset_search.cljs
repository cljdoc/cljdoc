(ns cljdoc.client.single-docset-search
  "Support for searching within a single library"
  (:require ["idb" :refer [openDB]]
            ["preact" :refer [h render]]
            ["preact/hooks" :refer [useEffect useRef useState]]
            [cljdoc.client.dom :as dom]
            [cljdoc.client.flow :as flow]
            [cljdoc.client.single-docset-search.logic :as search-logic]
            [clojure.string :as str]))

(def SEARCHSET_VERSION 6)

(defn- clamp [value min-value max-value]
  (min (max value min-value) max-value))

(defn- is-expired [date-string]
  (let [date (js/Date. date-string)
        expires-at (js/Date.)
        now (js/Date.)]
    (.setDate expires-at (inc (.getDate date)))
    (> now expires-at)))

(defn- ^:async evict-bad-search-sets [db]
  (let [keys (js-await (.getAllKeys db "searchsets"))]
    (doseq [key keys]
      (let [stored-searchset (js-await (.get db "searchsets" key))]
        (when (and stored-searchset
                   (or (not= (:version stored-searchset) SEARCHSET_VERSION)
                       (is-expired (:stored-at stored-searchset))
                       (not (seq stored-searchset))))
          (js-await (.delete db "searchsets" key))))))
  db)

(defn- ^:async fetch-index-items [url db]
  (let [stored-searchset (js-await (.get db "searchsets" url))]
    (if (and stored-searchset
             (= (:version stored-searchset) SEARCHSET_VERSION)
             (not (is-expired (:stored-at stored-searchset)))
             (seq stored-searchset))
      (:indexItems stored-searchset)
      (let [response (js-await (js/fetch url))
            search-set (js-await (.json response))
            items (->> [(mapv #(assoc % :kind :namespace) (:namespaces search-set))
                        (mapv #(assoc % :kind :def) (:defs search-set))
                        (->> (:defs search-set)
                             (keep #(when-let [members (seq (:members %))]
                                      (mapv (fn [m]
                                              (assoc m
                                                     :namespace (:namespace %)
                                                     :protocol-name (:name %)))
                                            members)))
                             (mapcat identity)
                             (mapv #(assoc % :kind :def)))
                        (mapv #(assoc % :kind :doc) (:docs search-set))]
                       (reduce into [])
                       (map-indexed (fn [ndx item]
                                      (assoc item :id ndx)))
                       doall)]
        (js-await (.put db "searchsets"
                        {:storedAt (.toISOString (js/Date.))
                         :version SEARCHSET_VERSION
                         :indexItems items}
                        url))
        items))))

(defn- ResultIcon [{:keys [item]}]
  (let [colors {"NS"  "bg-light-purple"
                "DOC" "bg-green"
                "VAR" "bg-dark-blue"
                "MAC" "bg-dark-red"
                "PRO" "bg-light-red"}
        default-color "bg-black-70"
        {:keys [text label]} (case (:kind item)
                               "namespace" {:text "NS"}
                               "def" {:label (:type item)
                                      :text (-> item :type (subs 0 3) str/upper-case)}
                               "doc" {:text "DOC"})
        color (get colors text default-color)]
    #jsx [:<>
          [:div {:class (str "dib pa1 white-90 br1 mr2 tc f6 " color)
                 :style "width:2.5rem; font-size: 13px; margin-bottom: 2px;"
                 :aria-label label
                 :title label}
           text]]))

(defn- ResultName [{:keys [item]}]
  (if (= "def" (:kind item))
    #jsx [:<>
          [:div {:class "mb1"}
           [:span (:namespace item)] "/" (:name item)]]
    #jsx [:<>
          [:div {:class "mb1"} (:name item)]]))

(defn- ResultProtocol [{:keys [item]}]
  (when (:protocol-name item)
    #jsx [:<> [:div {:class "ml2 nowrap"}
               (h ResultIcon {:item {:kind "def" :type "protocol"}})
               [:span (:protocol-name item)]]]))

(defn- ResultListItem [{:keys [searchResult selected onClick onMouseDown]}]
  (let [item (useRef nil)]
    (useEffect (fn []
                 (when (and (.-current item) selected)
                   (.scrollIntoView (.-current item) {:block "nearest"})))
               [(.-current item) selected])
    (let [result (:doc searchResult)]
      #jsx [:<>
            [:li {:class (cond-> "pa2 bb b--light-gray"
                           selected (str " bg-light-blue"))
                  :ref item}
             [:a {:class "no-underline black"
                  :href (:path result)
                  :onMouseDown (fn [e] (when onMouseDown (onMouseDown e)))
                  :onclick (fn [e] (when onClick (onClick e)))}
              [:div {:class "flex flex-row items-end"}
               (h ResultIcon {:item result})
               [:div {:class "flex flex-column"}
                (h ResultName {:item result})]
               [:div {:class "flex"}
                (h ResultProtocol {:item result})]]]]])))

(defn- SingleDocsetSearch [{:keys [url]}]
  (let [[db set-db!] (useState nil)
        [index-items set-index-items!] (useState nil)
        [search-index set-search-index!] (useState nil)

        [results set-results!] (useState [])
        [show-results set-show-results!] (useState false)
        [selected-ndx set-selected-ndx!] (useState nil)

        current-url (js/URL. js/window.location.href)
        [input-value set-input-value!] (useState (or (.get (.-searchParams current-url) "q")
                                                     ""))
        input-elem (useRef nil)]

    (useEffect
     (fn init-input-elem []
       (let [on-global-key-down (fn [{:keys [key] :as e}]
                                  (when (and (.-current input-elem)
                                             (or (.-metaKey e) (.-ctrlKey e))
                                             (= "/" key))
                                    (.focus (.-current input-elem))))]
         (.addEventListener js/document "keydown" on-global-key-down)
         (fn []
           (.removeEventListener js/document "keydown" on-global-key-down))))
     [input-elem])

    (useEffect
     (fn init-db []
       (let [db-version 1]
         (-> (openDB "cljdoc-searchsets-store"
                     db-version
                     {:upgrade (fn [db]
                                 (.createObjectStore db "searchsets"))})
             (.then evict-bad-search-sets)
             (.then set-db!)
             (.catch js/console.error))))
     [])

    (useEffect
     (fn fetch-index []
       (when db
         (-> (fetch-index-items url db)
             (.then set-index-items!)
             (.catch js/console.error))))
     [url db])

    (useEffect
     (fn build-index []
       (when index-items
         (set-search-index! (search-logic/build-search-index index-items))))
     [index-items])

    (let [on-arrow-up (fn []
                        (if (seq results)
                          (let [max (-> results count dec)]
                            (if (or (not selected-ndx) (zero? selected-ndx))
                              (set-selected-ndx! max)
                              (set-selected-ndx! (dec selected-ndx))))
                          (set-selected-ndx! nil)))

          on-arrow-down (fn []
                          (if (seq results)
                            (let [max (-> results count dec)]
                              (if (or (not selected-ndx) (= selected-ndx max))
                                (set-selected-ndx! 0)
                                (set-selected-ndx! (inc selected-ndx))))
                            (set-selected-ndx! nil)))

          clamp-selected-ndx (fn []
                               (when selected-ndx
                                 (if (not (seq results))
                                   (set-selected-ndx! nil)
                                   (let [ndx (clamp selected-ndx 0 (-> results count dec))]
                                     (when (not= ndx selected-ndx)
                                       (set-selected-ndx! ndx))))))
          on-result-navigation (fn [path]
                                 (when-let [input (.-current input-elem)]
                                   (let [redirect-to (js/URL. (str js/window.location.origin path))
                                         params (.-searchParams redirect-to)]
                                     (.set params "q" (.-value input))
                                     (set! (.-search redirect-to) (.toString params))
                                     (when (not= (.-href current-url) (.-href redirect-to))
                                       (.assign js/window.location (.toString redirect-to)))
                                     (when show-results
                                       (set-show-results! false)
                                       (.blur input)))))
          handle-search (flow/debounced 300
                                        (fn [query]
                                          (let [results (search-logic/search search-index query)]
                                            (if results
                                              (set-results! results)
                                              (set-results! []))
                                            (when-not show-results
                                              (set-show-results! true)))))]
      (clamp-selected-ndx)

      #jsx [:<>
            [:div
             [:form {:class "black-80 w-100"
                     :onSubmit (fn [e] (.preventDefault e))}
              [:div {:style "position: relative"}
               [:svg {:class "w1 h1"
                      :style "position: absolute; left: 0.58rem; top: 0.58rem; z-index: 1; stroke: currentColor; stroke-width: 3; fill: none"
                      :viewBox "0 0 24 24"
                      :xmlns "http://www.w3.org/2000/svg"}
                [:circle {:cx "11.963" :cy "8.7873" :r "7.2449"}]
                [:line {:x1 "16.075" :y1 "15.902" :x2 "20.357" :y2 "23.212"}]]
               [:input {:name "single-docset-search-term"
                        :type "text"
                        :aria-describedby "single-docset-search-term-description"
                        :class "input-reset ba b--black-20 pa2 pl4 db br1 w-100"
                        :value input-value
                        :disabled (not search-index)
                        :placeholder (if search-index "Search..." "Loading...")
                        :ref input-elem
                        :onFocus (fn [{:keys [target] :as _e}]
                                   (handle-search (.-value target)))
                        :onBlur (fn [{:keys [target] :as _e}]
                                  (dom/toggle-class target "b--blue")
                                  (when show-results
                                    (set-show-results! false)))
                        :onKeyDown (fn [{:keys [target key] :as e}]
                                     (case key
                                       "Escape" (if show-results
                                                  (set-show-results! false)
                                                  (.blur target))
                                       "ArrowUp" (do
                                                   (.preventDefault e)
                                                   (when-not show-results
                                                     (set-show-results! true))
                                                   (on-arrow-up))
                                       "ArrowDown" (do
                                                     (.preventDefault e)
                                                     (when-not show-results
                                                       (set-show-results! true))
                                                     (on-arrow-down))
                                       "Enter" (do
                                                 (.preventDefault e)
                                                 (if show-results
                                                   (when (and selected-ndx (seq results))
                                                     (on-result-navigation
                                                      (-> results
                                                          (get selected-ndx)
                                                          :doc
                                                          :path)))
                                                   (set-show-results! true)))
                                       nil))
                        :onInput (fn [{:keys [target] :as e}]
                                   (.preventDefault e)
                                   (let [value (.-value target)]
                                     (set-input-value! value)
                                     (handle-search value)))}]]
              (when (and show-results (seq results))
                #jsx [:<>
                      [:ol {:class "list pa0 ma0 no-underline black bg-white br--bottom ba br1 b--blue absolute overflow-y-scroll"
                            :style "z-index:1; max-width: 90vw; max-height: 80vh"}
                       (-> (for [[ndx result] (map-indexed vector results)]
                             (h ResultListItem {:searchResult result
                                                :index ndx
                                                :selected (= selected-ndx ndx)
                                                :onMouseDown (fn [e] (.preventDefault e))
                                                :onClick (fn [e]
                                                           (.preventDefault e)
                                                           (on-result-navigation (-> result :doc :path)))}))
                           doall)]])]]])))

(defn ^:async init []
  (let [single-docset-search-node (dom/query "[data-id='cljdoc-js--single-docset-search']")
        url (some-> single-docset-search-node .-dataset .-searchsetUrl)]
    (when (and single-docset-search-node
               (string? url))
      (render (h SingleDocsetSearch {:url url})
              single-docset-search-node))))

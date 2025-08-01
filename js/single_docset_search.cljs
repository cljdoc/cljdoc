(ns single-docset-search
  (:require ["./dom" :as dom]
            ["preact/hooks" :refer [useEffect useRef useState]]
            ["./search" :refer [debounced]]
            ["idb" :refer [DBSchema IDBPDatabase openDB]]
            ["elasticlunr$default" :as elasticlunr]))

(def SEARCHSET_VERSION 3)

;; TODO: tests for tokenization?
(defn- tokenize [s]
  (when s
    (let [candidate-tokens (-> s
                               .toString
                               .trim
                               .toLowerCase
                               (.split #"\s+"))
          long-all-punctuation-regex #"^[^a-z0-9]{7,}$"
          standalone-comment-regex #"^;+$"
          superfluous-punctutation-regex #"^[.,]+|[.,]+$"
          ;; strip leading and trailing periods and commas
          ;; this gets rid of normal punctuation
          ;; we leave in ! and ? because they can be interesting in var names
          trim-superfluous-punctuation (fn [candidate]
                                         (.replaceAll candidate superfluous-punctutation-regex ""))]
      (reduce (fn [tokens candidate]
                ;; keep tokens like *, <, >, +, ->> but skip tokens like ===============
                (if (or (.test long-all-punctuation-regex candidate)
                        (.test standalone-comment-regex candidate))
                  tokens
                  (let [token (trim-superfluous-punctuation candidate)]
                        (if (seq token)
                          (conj tokens token)
                          tokens))))
              []
              candidate-tokens))))

(defn- sub-tokenize [tokens]
  ;; only split on embedded forward slashes for now
  (let [split-char-regex #"\/"
        split-chars-regex #"\/+"]

    (reduce (fn [acc token]
              (if-not (.test split-char-regex token)
                acc
                (->> (.split token split-chars-regex)
                     (remove #(zero? (count %)))
                     (into acc))))
            []
            tokens)))

;; override default elastic lunr tokenizer
(set! elasticlunr.tokenizer (fn [s]
                              (.concat (tokenize s) (sub-tokenize s))))

(defn- clamp [value min-value max-value]
  (min (max value min-value) max-value))

(defn ^:async mount-single-docset-search []
  (let [single-docset-search-node (.querySelector document "[data-id='cljdoc-js--single-docset-search']")
        url (some-> single-docset-search-node .-dataset .searchsetUrl)]
    (when (and single-docset-search-node
               (string? url))
      (render #jsx [:<> [:SingleDocsetSearch {:url url}]]
              single-docset-search-node))))

(defn- is-expired [date-string]
  (let [date (Date. date-string)
        expires-at (Date.)
        now (Date .)]
    (.setDate expired-at (inc (.getDate date)))
    (> now expires-at)))

(defn- ^:async evict-bad-search-sets [db]
  (let [keys (js-await (.getAllKeys db "searchsets"))]
    (doseq [key keys]
      (let [stored-searchset (js-await (.get db searchsets key))]
        (when (and stored-searchset
                   (or (not= (:version stored-searchset) SEARCHSET_VERSION)
                       (is-expired (:stored-at stored-searchset))
                       (not (seq stored-searchset))))
          (js-await (.delete db "searchsets" key)))))))

(defn- ^:async fetch-index-items [url db]
  (let [stored-searchset (js-await (.get db "searchsets" url))]
    (when (and stored-searchset
               (= (:version stored-searchset) SEARCHSET_VERSION)
               (not (is-expired (:stored-at stored-searchset)) )
               (seq store-searchset))
      (:indexItems stored-searchset))))

(defn- build-search-index [index-items]
  (let [search-index (elasticlunr
                      (fn [index]
                        (.setRef index "id")
                        (.addField index "name")
                        (.addField index "doc")
                        (-> index .-pipeline .reset)
                        (.saveDocument index true)))]
    (doseq [item index-items]
      (.addDoc search-index item))

    search-index))


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
                                      :text (-> item :type (subs 0 3) .toUpperCase)}
                               "doc" {:text "doc"})
        color (get colors text default-color)]
    #jsx [:<>
          [:div {:class (str "pa1 white-90 br1 mr2 tc f6 " color)
                 :style "width:2.5rem; fontSize: 13px; marginBottom: 2px"
                 :aria-label label
                 :title label}
           text]]))


(defn- ResultName [{:keys [item]}]
  (if (= "def" (:kind item))
    #jsx [:<>
          [:div {:class "mb1"}
           ;; TODO: class empty string?
           [:span {:class ""} (:namespace item)]
           (:name item)]]
    #jsx [:<>
          [:div {:class "mb1"} (:name item)]]))

(defn- ResultListItem [{:keys [searchResult index selected onClick onMouseDown]}]
  (let [item (useRef nil)]
    (useEffect (fn []
                 (when (and (.-current item) selected)
                   (.scrollIntoView (.-current item) {:block "nearest"})))
               [(.-current item) selected])
    (let [result (.-doc searchResult)]
      #jsx [:<>
            [:li {:class "pa2 bb b--light-gray"}
             :ref item
             [:a {:class "no-underline black"
                  :href (.-path result)
                  :onMouseDown (fn [e] (when onMouseDown (onMouseDown e)))
                  :onclick (fn [e] (when onClick (onClick e)))}
              [:div {:class "flex flex-row items-end"}
               [:ResultIcon {:item result}]
               [:div {:class "flex flex-column"}
                [:ResultName {:item result}]]]]]])))

;; TODO: hmmm...
(defn- search [search-index query]
  (when search-index
    (let [exact-tokens (tokenize query)
          field-queries [{:field "name" :boost 10 :tokens exact-tokens}
                         {:field "doc" :boost 5 :tokens exact-tokens}]
          query-results {}]
      (for [fq field-queries]
        (let [search-config { (:field fq) {:boost (:boost fq)
                                           :bool "OR"
                                           :expand true}}
              field-search-results (.fieldSearch search-index
                                                 (:tokens fq)
                                                 (:field fq)
                                                 search-config)]
          (.log console "fsr" field-search-result)

          ()))
      ))
  )

(def ^:private debounced-search (debounced 300 search))

(defn SingleDocsetSearch [{:keys [url]}]
  (let [[db set-db!] (useState IDBPDatabase)
        [index-items set-index-items!] (useState IndexItem [])
        [search-index set-search-index!] (useState Index)

        [results set-results!] (useState [])
        [show-results set-show-results!] (useState false)
        [selected-ndx set-selected-ndx!] (useState nil)

        current-url (URL. window.location.href)
        [input-value set-input-value!] (useState (or (.get (.-searcParams current-url) "q")
                                                     ""))
        input-elem (useRef nil)]

    (useEffect
     (fn []
       (let [handle-key-down (fn [{:keys [key] :as e}]
                               (when (and (.-current input-elem)
                                          (or (.-metaKey e) (.-ctrlKey e))
                                          (= "/" key))
                                 (.focus (.-current input-elem))))]
         (.addEventListener document "keydown" handle-key-down)
         (fn []
           (.removeEventListener document "keydown" handle-key-down))))
     [input-elem])

    (useEffect
     (fn []
       (->
        (openDB "cljdoc-searchsets-store" 1
                {:upgrade (fn [db]
                            (.createObjectStore "searchsets"))})
        (.then evictBadSearchSets)
        (.then setDb)
        (.catch console.error))
       []))

    (useEffect
     (fn []
       (when db
         (-> (fetchIndexItems url db)
             (.then setIndexItems)
             (.catch console.error))))
     [url db])

    (useEffect
     (fn []
       (when index-items
         (set-search-index! (build-search-index index-items))))
     [index-items])

    (let [on-arrow-up (fn []
                        (if (seq results)
                          (let [max (dec (-> results count dec))]
                            (if (or (not selected-ndx) (zero? selected-ndx))
                              (set-selected-ndx! max)
                              (set-selected-ndx! (dec selected-ndx))))
                          (set-selected-ndx! nil)))

          on-arrow-down (fn []
                          (if (seq results)
                            (let [max (dec (-> results count dec))]
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
                                   (let [redirect-to (URL. (str window.location.origin path))
                                         params (.-searchParams redirect-to)]
                                     (.set params "q" (.-value input))
                                     (set! (.-search redirect-to) (.toString params))
                                     (when (not= (.-href current-url) (.-href redirec-to))
                                       (.assign window.location (.toString redirect-to)))
                                     (when show-results
                                       (set-show-results! false)
                                       (.blur input))
                                   )))]
      (clamp-selected-ndx)

      #jsx [:<>
            [:div
             [:form {:class "black-80 w-100"
                     :onSubmit (fn [e] (.preventDefault e))}
              [:div {:style "position: relative"}
               [:img {:src "/search-icon.svg"
                      :class "w1 h1"
                      :style "position: absolute; left: 0.58rem; right: 0.58rem; zIndex 1"}]
               [:input {:name "single-docset-search-term"
                        :type "text"
                        :aria-describedby "single-docset-search-term-description"
                        :class "input-reset ba b--black-20 pa2 pl4 db br1 w-100"
                        :value input-value
                        :disabled (not search-index)
                        :placeholder (if search-index "Search..." "Loading...")
                        :ref input-elem
                        :onFocus (fn [{:keys [target] :as e}]
                                   (dom/toggle-class target "b--blue")
                                   (-> (debounced-search search-index (.-value target))
                                       (.then (fn [results]
                                                (if results
                                                  (set-results! results)
                                                  (set-results! []))
                                                (when-not show-results
                                                  (set-show-results! true))))
                                       (.catch console.error)))
                        :onBlur (fn [{:keys [target] :as e}]
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
                                                   (when (and selected-ndx (seq results) )
                                                     (on-result-navigation
                                                       (-> results
                                                           (get selected-ndx)
                                                           :doc
                                                           :path)))
                                                   (set-show-results! true)))
                                       nil))
                        :onInput (fn [{:keys [target] as e}]
                                   (.preventDefault e)
                                   (set-input-value! (.-value input))
                                   (if (zero? (-> input .-value count))
                                     (set-results! [])
                                     (-> (debounced-search search-index (.-value input))
                                         (.then (fn [results]
                                                  (if results
                                                    (set-results! results)
                                                    (set-results! []))
                                                  (when-not show-results
                                                    (set-show-results! true))))
                                         (.catch console.error))))}]]
              (when (and show-results (seq results))
                [:ol {:class "list pa0 ma0 no-underline black bg-white br--bottom ba br1 b--blue absolute overflow-y-scroll"
                      :style "zIndex:1; maxWidth: 90vw; maxHeight: 80vh"}
                 (for [[ndx result] (map-indexed vector results)]
                   [:ResultListItem {:searchResult result
                                     :index index
                                     :selected (= selected-ndx ndx)
                                     :onMouseDown (fn [e] (.preventDefault e))
                                     :onClick (fn [e]
                                                (.preventDefault e)
                                                (on-result-navigation (-> result :doc :path)))}])])]]])))

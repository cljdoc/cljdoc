(ns single-docset-search
  (:require ["./dom" :as dom]
            ["./search" :refer [debounced]]
            ["elasticlunr$default" :as elasticlunr]
            ["idb" :refer [openDB]]
            ["preact" :refer [render]]
            ["preact/hooks" :refer [useEffect useRef useState]]
            [clojure.string :as str]))

(def SEARCHSET_VERSION 4)

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
                                         (str/replace candidate superfluous-punctutation-regex ""))]
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
  (let [single-docset-search-node (dom/query-doc "[data-id='cljdoc-js--single-docset-search']")
        url (some-> single-docset-search-node .-dataset .-searchsetUrl)]
    (when (and single-docset-search-node
               (string? url))
      (.log js/console "mounting dss" url)
      (render #jsx [:<> [:SingleDocsetSearch {:url url}]]
              single-docset-search-node))))

(defn- is-expired [date-string]
  (let [date (js/Date. date-string)
        expires-at (js/Date.)
        now (js/Date.)]
    (.setDate expires-at (inc (.getDate date)))
    (> now expires-at)))

(defn- ^:async evict-bad-search-sets [db]
  (.log js/console "evict-bad-search-sets" db)
  (let [keys (js-await (.getAllKeys db "searchsets"))]
    (.log js/console "evict check" keys)
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
    (.log js/console "fetch index items" stored-searchset)
    (if (and stored-searchset
             (= (:version stored-searchset) SEARCHSET_VERSION)
             (not (is-expired (:stored-at stored-searchset)))
             (seq stored-searchset))
      (:indexItems stored-searchset)
      (do
        (.log js/console "fetching items from" url)
        (let [response (js-await (js/fetch url))
              search-set (js-await (.json response))
              items (->> [(mapv #(assoc % :kind :namespace) (:namespaces search-set))
                          (mapv #(assoc % :kind :def) (:defs search-set))
                          (mapv #(assoc % :kind :doc) (:docs search-set))]
                         (reduce (fn [acc n]
                                   (into acc n))
                                 [])
                         (map-indexed (fn [ndx item]
                                        (assoc item :id ndx))))]
          (.log js/console "fetched items" items)

          (js-await (.put db "searchsets"
                          {:storedAt (.toISOString (js/Date.))
                           :version SEARCHSET_VERSION
                           :indexItems items}
                          url))

          (.log js/console "items put" db)
          items)))))

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
  (.log js/console "result icon" item)
  (.log js/console "result icon type" (:type item))
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
          [:div {:class (str "pa1 white-90 br1 mr2 tc f6 " color)
                 :style "width:2.5rem; font-size: 13px; margin-bottom: 2px;"
                 :aria-label label
                 :title label}
           text]]))

(defn- ResultName [{:keys [item]}]
  (if (= "def" (:kind item))
    #jsx [:<>
          [:div {:class "mb1"}
           [:span (:namespace item)]"/"(:name item)]]
    #jsx [:<>
          [:div {:class "mb1"} (:name item)]]))

(defn- ResultListItem [{:keys [searchResult selected onClick onMouseDown]}]
  (let [item (useRef nil)]
    (.log js/console "rli" searchResult)
    (useEffect (fn []
                 (when (and (.-current item) selected)
                   (.scrollIntoView (.-current item) {:block "nearest"})))
               [(.-current item) selected])
    (let [result (:doc searchResult)]
      (.log js/console "rli path" (:path result))
      #jsx [:<>
            [:li {:class (cond-> "pa2 bb b--light-gray"
                           selected (str " bg-light-blue"))
                  :ref item}
             [:a {:class "no-underline black"
                  :href (:path result)
                  :onMouseDown (fn [e] (when onMouseDown (onMouseDown e)))
                  :onclick (fn [e] (when onClick (onClick e)))}
              [:div {:class "flex flex-row items-end"}
               [:ResultIcon {:item result}]
               [:div {:class "flex flex-column"}
                [:ResultName {:item result}]]]]]])))

(defn- search [search-index query]
  (when search-index
    (.log js/console "search" search-index query)
    (let [exact-tokens (tokenize query)
          _ (.log js/console "tokens" exact-tokens)
          field-queries [{:field "name" :boost 10 :tokens exact-tokens}
                         {:field "doc" :boost 5 :tokens exact-tokens}]
          query-results {}]
      ;; until I clearly understand what the intent here...
      ;; ...for now we'll mimic the original mutable typescript implementation
      (doseq [field-query field-queries]
        (let [search-config {(:field field-query) {:boost (:boost field-query)
                                                   :bool "OR"
                                                   :expand true}}
              field-search-results (.fieldSearch search-index
                                                 (:tokens field-query)
                                                 (:field field-query)
                                                 search-config)]

          ;; boost field
          (let [boost (:boost field-query)]
            (doseq [doc-ref (js/Object.keys field-search-results)]
              (let [current-value (aget field-search-results doc-ref)]
                (aset field-search-results doc-ref
                      (* current-value boost)))))

          ;; accumulate results
          (doseq [doc-ref (js/Object.keys field-search-results)]
            (aset query-results doc-ref (+ (or (aget query-results doc-ref) 0)
                                           (aget field-search-results doc-ref))))))

      (let [results []]
        (doseq [doc-ref (js/Object.keys query-results)]
          (conj! results {:ref doc-ref :score (aget query-results doc-ref)}))

        (.sort results (fn [a b]
                         (- (:score b) (:score a))))


        (.log js/console "search-index" search-index)
        (.log js/console "doc store" (:documentStore search-index))
        (let [results-with-docs (mapv (fn [r]
                                        ;; (.log js/console "rwd" r)
                                        ;; (.log js/console "rwd ref" (:ref r))
                                        ;; (.log js/console "rwd doc" (.getDoc (:documentStore search-index) (:ref r)))
                                        ;; (.log js/console "rwd ds" (:documentStore search-index))
                                        {:result r
                                         :doc (.getDoc (:documentStore search-index) (:ref r))})
                                      results)
              seen #{}]
          (reduce (fn [acc {:keys [doc] :as n}]
                    (if-not (contains? #{"namespace" "def"} (:kind doc))
                      (do
                        (.log js/console "na")
                        (conj acc n))
                      ;; stringify unique id to json... JS does not do object equality
                      (let [unique-id (.stringify js/JSON (select-keys doc [:kind :name :path :namespace]))]
                        (.log js/console "unique-id" unique-id)
                        (if (contains? seen unique-id)
                          acc
                          (do
                            (conj! seen unique-id)
                            (conj acc n))))))
                  []
                  results-with-docs))))))

(def ^:private debounced-search (debounced 300 search))

(defn SingleDocsetSearch [{:keys [url]}]
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
       (.log js/console "init input elem")
       (let [handle-key-down (fn [{:keys [key] :as e}]
                               (when (and (.-current input-elem)
                                          (or (.-metaKey e) (.-ctrlKey e))
                                          (= "/" key))
                                 (.focus (.-current input-elem))))]
         (.addEventListener js/document "keydown" handle-key-down)
         (fn []
           (.removeEventListener js/document "keydown" handle-key-down))))
     [input-elem])

    (useEffect
     (fn init-db []
       (.log js/console "init db")
       (let [db-version 1]
         (-> (openDB "cljdoc-searchsets-store"
                     db-version
                     {:upgrade (fn [db]
                                 (.log js/console "creating object store" db)
                                 (.createObjectStore db "searchsets"))})
             (.then evict-bad-search-sets)
             (.then set-db!)
             (.catch js/console.error))))
     [])

    (useEffect
     (fn fetch-index []
       (when db
         (.log js/console "docset fetching" url db)
         (-> (fetch-index-items url db)
             (.then set-index-items!)
             (.catch js/console.error))))
     [url db])

    (useEffect
     (fn build-index []
       (.log js/console "build search index")
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
                                   (let [redirect-to (js/URL. (str js/window.location.origin path))
                                         params (.-searchParams redirect-to)]
                                     (.set params "q" (.-value input))
                                     (set! (.-search redirect-to) (.toString params))
                                     (when (not= (.-href current-url) (.-href redirect-to))
                                       (.assign js/window.location (.toString redirect-to)))
                                     (when show-results
                                       (set-show-results! false)
                                       (.blur input)))))]
      (clamp-selected-ndx)

      #jsx [:<>
            [:div
             [:form {:class "black-80 w-100"
                     :onSubmit (fn [e] (.preventDefault e))}
              [:div {:style "position: relative"}
               [:img {:src "/search-icon.svg"
                      :class "w1 h1"
                      :style "position: absolute; left: 0.58rem; top: 0.58rem; z-index: 1;"}]
               [:input {:name "single-docset-search-term"
                        :type "text"
                        :aria-describedby "single-docset-search-term-description"
                        :class "input-reset ba b--black-20 pa2 pl4 db br1 w-100"
                        :value input-value
                        :disabled (not search-index)
                        :placeholder (if search-index "Search..." "Loading...")
                        :ref input-elem
                        :onFocus (fn [{:keys [target] :as e}]
                                   (.log console "onFocus" target)
                                   (dom/toggle-class target "b--blue")
                                   (-> (debounced-search search-index (.-value target))
                                       (.then (fn [results]
                                                (.log console "results" results)
                                                (if results
                                                  (set-results! results)
                                                  (set-results! []))
                                                (when-not show-results
                                                  (set-show-results! true))))
                                       (.catch js/console.error)))
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
                                   (set-input-value! (.-value target))
                                   (if (zero? (-> target .-value count))
                                     (set-results! [])
                                     (-> (debounced-search search-index (.-value target))
                                         (.then (fn [results]
                                                  (if results
                                                    (set-results! results)
                                                    (set-results! []))
                                                  (when-not show-results
                                                    (set-show-results! true))))
                                         (.catch console.error))))}]]
              (when (and show-results (seq results))
                #jsx [:<>
                      [:ol {:class "list pa0 ma0 no-underline black bg-white br--bottom ba br1 b--blue absolute overflow-y-scroll"
                            :style "z-index:1; max-width: 90vw; max-height: 80vh"}
                       (-> (for [[ndx result] (map-indexed vector results)]
                             #jsx [:ResultListItem {:searchResult result
                                               :index ndx
                                               :selected (= selected-ndx ndx)
                                               :onMouseDown (fn [e] (.preventDefault e))
                                               :onClick (fn [e]
                                                          (.preventDefault e)
                                                          (on-result-navigation (-> result :doc :path)))}])
                           doall)]])]]])))

(ns cljdoc.client.single-docset-search.logic
  (:require ["elasticlunr$default" :as elasticlunr]
            [clojure.string :as str]))

(defn tokenize [s]
  (if (not s)
    []
    (let [candidate-tokens (-> s
                               str
                               str/trim
                               str/lower-case
                               (str/split #"\s+"))
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

(defn sub-tokenize [tokens]
  ;; only split on embedded forward slashes for now
  (let [split-char-regex #"/"
        split-chars-regex #"/+"]
    (reduce (fn [acc token]
              (if-not (.test split-char-regex token)
                acc
                (->> (str/split token split-chars-regex)
                     (remove #(zero? (count %)))
                     (into acc))))
            []
            tokens)))

(defn tokenizer [s]
  (let [tokens (tokenize s)]
    (into tokens (sub-tokenize tokens))))

;; override default elastic lunr tokenizer used during indexing
(set! elasticlunr.tokenizer tokenizer)

(defn build-search-index [index-items]
  (let [search-index (elasticlunr
                      (fn [index]
                        (.setRef index "id")
                        (.addField index "name")
                        (.addField index "doc")
                        ;; remove all default pipeline functions: trimmer, stop word filter & stemmer
                        (-> index .-pipeline .reset)
                        (.saveDocument index true)))]
    (doseq [item index-items]
      (.addDoc search-index item))

    search-index))

(defn search [search-index query]
  (when search-index
    (let [exact-tokens (tokenize query)
          field-queries [{:field "name" :boost 10 :tokens exact-tokens}
                         {:field "doc" :boost 5 :tokens exact-tokens}]
          query-results {}]
      ;; ...for now we mimic the original mutable typescript implementation
      ;; which probably makes sense for perf
      (doseq [field-query field-queries]
        (let [search-config {(:field field-query) {:boost (:boost field-query)
                                                   :bool "OR"
                                                   :expand true}}]
          (when-let [field-search-results (.fieldSearch search-index
                                                        (:tokens field-query)
                                                        (:field field-query)
                                                        search-config)]
            ;; boost field
            (let [boost (:boost field-query)]
              (doseq [doc-ref (js/Object.keys field-search-results)]
                (let [current-value (get field-search-results doc-ref)]
                  (assoc! field-search-results doc-ref
                          (* current-value boost)))))

            ;; accumulate results
            (doseq [doc-ref (js/Object.keys field-search-results)]
              #_{:clj-kondo/ignore [:unused-value]}
              (assoc! query-results doc-ref (+ (or (get query-results doc-ref) 0)
                                               (get field-search-results doc-ref)))))))

      (let [results []]
        (doseq [doc-ref (js/Object.keys query-results)]
          #_{:clj-kondo/ignore [:unused-value]}
          (conj! results {:ref doc-ref :score (get query-results doc-ref)}))

        (.sort results (fn [a b]
                         (- (:score b) (:score a))))

        (let [results-with-docs (mapv (fn [r]
                                        {:result r
                                         :doc (.getDoc (:documentStore search-index) (:ref r))})
                                      results)
              seen #{}]
          (reduce (fn [acc {:keys [doc] :as n}]
                    (if-not (contains? #{"namespace" "def"} (:kind doc))
                      (conj acc n)
                      ;; stringify unique id to json... JS does not do object equality
                      (let [unique-id (.stringify js/JSON (select-keys doc [:kind :name :path :namespace]))]
                        (if (contains? seen unique-id)
                          acc
                          (do
                            #_{:clj-kondo/ignore [:unused-value]}
                            (conj! seen unique-id)
                            (conj acc n))))))
                  []
                  results-with-docs))))))

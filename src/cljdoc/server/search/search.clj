;; TODO Take download count into account - see "Integrating field values into the score" at https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/search/package-summary.html#changingScoring
(ns cljdoc.server.search.search
  "Search the Lucene index for artifacts best matching a query.

  ## Troubleshooting and tuning tips ##

  * Use `explain-top-n` to get a detailed analysis of the score of the top N results for a query
  * Print out a Query - it's toString shows nicely what is it composed of, boosts, etc.
  "
  (:import (org.apache.lucene.analysis TokenStream)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.document Document FeatureField)
           (org.apache.lucene.index DirectoryReader Term)
           (org.apache.lucene.search Query IndexSearcher TopDocs ScoreDoc BooleanClause$Occur PrefixQuery BoostQuery BooleanQuery$Builder TermQuery Query)
           (org.apache.lucene.store FSDirectory Directory)
           (java.nio.file Paths)))

(defn ^FSDirectory fsdir [index-dir] ;; BEWARE: Duplicated in artifact-indexer and search ns
  (FSDirectory/open (Paths/get index-dir (into-array String nil))))

(defn tokenize
  "Split search text into tokens. It is reasonable to use the same tokenization <> analyzer
   as when indexing."
  ([text] (tokenize (StandardAnalyzer.) text))
  ([analyzer text]
   (let [^TokenStream stream (.tokenStream analyzer "fake-field-name" text)
         attr                (.addAttribute stream org.apache.lucene.analysis.tokenattributes.CharTermAttribute)]
     (.reset stream)
     (take-while
       identity
       (repeatedly
         #(when (.incrementToken stream) (str attr)))))))

(def search-fields [:artifact-id :group-id :group-id-packages :description])

(def boosts
  "Boosts for selected artifact fields (artifact id > group id > description
   The numbers were determined somewhat randomly but seem to work well enough."
  {:artifact-id       3
   :group-id-packages 3
   :group-id          2
   :description       1})

(defn raw-field [field]
  (get {:artifact-id :artifact-id-raw
        :group-id    :group-id-raw}
       field))
;
;(defn lucene-quote-regexp
;  "Lucene RegExp is only a subset of Java RegExp so we cannot use Pattern/quote.
;   The only character likely to appear in search that we want to escape is '.',
;   used in group names.
;   See http://lucene.apache.org/core/8_0_0/core/org/apache/lucene/util/automaton/RegExp.html
;  "
;  [text]
;  (clojure.string/replace text "." "\\."))
;
;(defn ^String tokens->regexp [tokens]
;  (str
;    (->> tokens
;         (map lucene-quote-regexp)
;         (join "."))
;    ".*"))
;
;(defn ^Query regexp-query [field tokens]
;  (when-let [field (raw-field field)]
;    (RegexpQuery.
;      (term field (tokens->regexp tokens)))))

(defn term [field ^String value]
  (Term. (name field) value))

(defn ^Query boolean-query*
  ([queries] (boolean-query* BooleanClause$Occur/SHOULD queries))
  ([occur queries]
   (case (count queries)
     0 nil
     1 (first queries)
     (let [builder (BooleanQuery$Builder.)]
       (run! (fn [^Query q]
               (when q
                 (.add builder q occur)))
             queries)
       (.build builder)))))

(defn ^Query boolean-query
  ([occur-or-q & queries]
   (if (instance? BooleanClause$Occur occur-or-q)
     ;; NOTE: Order of subqueries does not matter so it's OK to re-order
     (boolean-query* occur-or-q queries)
     (boolean-query* (conj queries occur-or-q)))))

(defn ^Query exact-or-prefix-query
  "Check for 'token' or 'token.*'"
  [field token]
  (let [t (term field token)]
    (boolean-query
      (TermQuery. t)
      (BoostQuery.
        (doto (PrefixQuery. t)
          #_(.setRewriteMethod ScoringRewrite/SCORING_BOOLEAN_REWRITE))
        ;; Give a prefix less weight than an exact match
        0.5))))

(defn single-token->query [field ^String token match-mode]
  (case match-mode
    :exact (TermQuery. (term field token))
    :prefix (exact-or-prefix-query field token)))

(defn field-and-token->query [field token match-mode full-match-text]
  (-> (boolean-query
        (single-token->query field token match-mode)
        ;; Add an exact-match query to boost exact matches of the
        ;; whole name so that "re-frame" -> "re-frame:re-frame" comes first
        ;; FIXME This works for group OR artifact-id but breaks for `group/artifact` (x group-id-packages search looks at just the group => use same tokenization? Or split manually at `/`?)
        (when-let [raw-fld (and full-match-text (raw-field field))]
          (TermQuery. (term raw-fld full-match-text))))
      (BoostQuery.
        (get boosts field))))

(defn ^Query boost-by-downloads
  "Boost artifacts based on the number of their downloads.
   Params:
    - query - the basic query to boost

   See
    - https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/search/package-summary.html#changingScoring
    - https://www.elastic.co/guide/en/elasticsearch/reference/current/query-dsl-rank-feature-query.html#_saturation_2
  "
  [query]
  (if @_downl-on
    (boolean-query
      query
      ;; FIXME Returns result with high download count with no term matching
      (BoostQuery.
        ;; NOTE: This will return a score in (0, 1) and < 0.5 if # downloads < ± geometrical average
        ; log ex. boosts [downl boost]: 0 1.3e-4, 10 0.01, 100 0.12, 1k 0.57, 10k 0.93, 100k 0.99, mil 0.99 @ b=1f
        (FeatureField/newSaturationQuery "downloads" "downloads")
        ; log ex. boosts [downl boost]: 0 2.x, 10 3, 100 4.7, 1k 7, 10k 9.2, 100k 11.5, mil 13.8 @ b=1f
        ;(FeatureField/newLogQuery "downloads" "downloads" 1 10)
        0.3))
    query))

(defn token->query
  "Create a Lucene Query from a tokenized search string.
   The search is expected to be just search terms, we do not support/expect any
   special search modifiers such as regexp, wildcards, or fuzzy indicators, as
   Lucene's QueryParser does."
  [token match-mode full-match-text]
  (->> search-fields
       (map
         #(field-and-token->query
            % token match-mode full-match-text))
       (apply boolean-query)))

(defn ^Query parse-query [^String query-text]
  (let [tokens (tokenize query-text)
        multitoken? (next tokens)
        match-modes (->> (cons :prefix (repeat :exact))
                         (take (count tokens))
                         reverse)]
    (boost-by-downloads
      (TermQuery. (term :group-id query-text)))
    ;; FIXME Re-add:
    #_(->> (map
             #(token->query %1 %2 (when multitoken? query-text))
             tokens match-modes)
           (apply boolean-query BooleanClause$Occur/MUST)
           ;; FIXME Enable 👇 and test thoroughly (also for artifacts with no downloads info)
           (boost-by-downloads))))

(defn search->results
  "NOTE: We take the result formatting function `f` as a parameter because it
   must be run within the context of our `with-open`."
  ([index-dir ^String query-in f]
   (search->results index-dir query-in 30 f))
  ([index-dir ^String query-in ^long max-results f]
   {:pre [(or (string? index-dir) (instance? Directory index-dir))]}
   (with-open [reader (DirectoryReader/open
                        ^Directory
                        (if (instance? Directory index-dir)
                          index-dir
                          (fsdir ^String index-dir)))]
     (let [searcher         (IndexSearcher. reader)
           ;parser           (QueryParser. "artifact-id" analyzer)
           ;query            (.parse parser query-str)
           ^Query query     (if (instance? Query query-in)
                              query-in
                              (parse-query query-in))
           ^TopDocs topdocs (.search searcher query max-results)
           hits             (.scoreDocs topdocs)]
       (f {:topdocs topdocs :score-docs hits :searcher searcher :query query})))))

(defn format-results
  "Format search results into what the frontend expects"
  [hits-cnt docs]
  {:count   hits-cnt
   :results (map
              (fn [{:keys [^Document doc score]}]
                {:artifact-id (.get doc "artifact-id")
                 :group-id    (.get doc "group-id")
                 :description (.get doc "description")
                 :downloads (.get doc "dbg-downloads") ; FIXME - tmp, comment out
                 ;:origin (.get doc "origin")
                 ;; The first version appears to be the latest so this is OK:
                 :version (.get doc "versions")
                 :score       score})
              docs)})

(defn search [index-dir ^String query-in]
  {:pre [(or (string? index-dir) (instance? Directory index-dir))]}
  (search->results
    index-dir query-in
    #(format-results
       (-> (:topdocs %) (.-totalHits) (.-value))
       (doall
         (map
           (fn [^ScoreDoc h]
             {:score (.score h)
              :doc (.doc (:searcher %) (.-doc h))})
           (:score-docs %))))))

(defn suggest
  "Provides suggestions for auto-completing the search terms the user is typing.
   Note: In Firefox, the response needs to reach the browser within 500ms otherwise it will be discarded.
   For more information, see:
   - http://www.opensearch.org/Specifications/OpenSearch/Extensions/Suggestions/1.0
   - https://developer.mozilla.org/en-US/docs/Web/OpenSearch
   - https://developer.mozilla.org/en-US/docs/Archive/Add-ons/Supporting_search_suggestions_in_search_plugins
   "
  [index-dir query-in]
  (let [suggestions
        (search->results
          index-dir query-in 5
          #(->> (:score-docs %)
                (mapv
                  (fn [^ScoreDoc h]
                    (let [doc (.doc (:searcher %) (.-doc h))]
                      (str
                        (.get doc "group-id")
                        "/"
                        (.get doc "artifact-id")))))))]
    [query-in suggestions]))

(defn explain-top-n
  "Debugging function to print out the score and its explanation of the
   top `n` matches for the given query.
   "
  ([index-dir query-in] (explain-top-n 5 index-dir query-in))
  ([n index-dir query-in]
   (search->results
     index-dir query-in
     (fn [{:keys [searcher score-docs ^Query query]}]
       (run!
         #(println
            (.get (.doc searcher (.-doc %)) "id")
            (.explain searcher query (.-doc %)))
         (take n score-docs))))))

(comment

  (let [db-spec {:classname "org.sqlite.JDBC", :subprotocol "sqlite", :subname "data/cljdoc.db.sqlite"}]
    (time (cljdoc.server.search.artifact-indexer/download-and-index! "data/index" db-spec :force)))

  (def idx-dir (org.apache.lucene.store.MMapDirectory. (Paths/get "mem-idx" (into-array String nil))))

  (cljdoc.server.search.artifact-indexer/index!
    idx-dir
    [{:group-id "test" :artifact-id "none"}
     {:group-id "test" :artifact-id "ten" :downloads 10}
     {:group-id "test" :artifact-id "hundred" :downloads 100}
     {:group-id "test" :artifact-id "thousand" :downloads 1000}
     {:group-id "test" :artifact-id "ten-k" :downloads 10000}
     {:group-id "test" :artifact-id "hundred-k" :downloads 100000}
     {:group-id "test" :artifact-id "mil" :downloads 1000000}])

  (defn ->list [res]
    (->> res
         :results
         (map #(assoc % :downl (download-cnt %)))
         (map (juxt :group-id :artifact-id :score :downloads))))

  (defn search+ [& args]
    (->list (apply search args)))


  (swap! _downl-on not)

  (explain-top-n 10 idx-dir "whatever")

  (search+ "data/index" "metosin muunta")
  ;; FIXME metosin:muuntaja comes 6th because the partial match on 'muuntaja' is
  ;; less worth than 1) double match on metosin (in a, g) and 2) match on g=metosin
  ;; and a random, rare artifact name => with high idf

  ;; DESIRED: metosin/muntaja first thx to highest download count
  (explain-top-n 4 "data/index" "metosin muunta")

  (search+ "data/index" "re-frame")
  (search+ "data/index" "clojure")
  (search+ "data/index" "testit")
  (search+ "data/index" "ring")
  (search+ "data/index" "conc")
  (explain-top-n 3 "data/index" "clojure")

  nil)
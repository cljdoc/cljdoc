;; TODO Take download count into account - see "Integrating field values into the score" at https://lucene.apache.org/core/8_0_0/core/org/apache/lucene/search/package-summary.html#changingScoring
(ns cljdoc.server.search.search
  (:import (org.apache.lucene.analysis TokenStream)
           (org.apache.lucene.analysis.standard StandardAnalyzer)
           (org.apache.lucene.document Document)
           (org.apache.lucene.index DirectoryReader Term)
           (org.apache.lucene.search Query IndexSearcher TopDocs ScoreDoc BooleanClause$Occur PrefixQuery BoostQuery BooleanQuery$Builder TermQuery Query)
           (org.apache.lucene.store FSDirectory)
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
   (let [builder (BooleanQuery$Builder.)]
     (run! (fn [^Query q]
             (when q
               (.add builder q occur)))
           queries)
     (.build builder))))

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

(defn ^Query multi-tokens->query
  "-> `TermQuery* PrefixQuery`"
  [field tokens]
  (let [exact-qs (map
                   #(TermQuery. (term field %))
                   (butlast tokens))
        prefix-q (exact-or-prefix-query field (last tokens))]
    (apply boolean-query
           prefix-q exact-qs)))

(defn single-token->query [field ^String token]
  (exact-or-prefix-query field token))

(def boosts
  "Boosts for selected artifact fields (artifact id > group id > description
   The numbers were determined somewhat randomly but seem to work well enough."
  {:artifact-id       3
   :group-id-packages 3
   :group-id          2
   :description       1})

(defn tokens->query
  "Create a Lucene Query from a tokenized search string.
   The search is expected to be just search terms, we do not support/expect any
   special search modifiers such as regexp, wildcards, or fuzzy indicators, as
   Lucene's QueryParser does."
  [field tokens query-text]
  (BoostQuery.
    (if (next tokens)
      (boolean-query
        (multi-tokens->query field tokens)
        ;; Add an exact-match query to boost exact matches of the
        ;; whole name so that "re-frame" -> "re-frame:re-frame" comes first
        (when-let [field (raw-field field)]
          (TermQuery. (term field query-text))))
      (single-token->query field (first tokens)))
    (get boosts field)))

(defn ^Query parse-query [^String query-text]
  (when-let [tokens (seq (tokenize query-text))]
    (->> [:artifact-id :group-id :group-id-packages :description]
         (map #(tokens->query % tokens query-text))
         (apply boolean-query))))

(defn search->results [^String index-dir ^String query-in f]
  ;; TODO If no matches, try again but using OR instead of AND in multi-tokens->query?
  (with-open [reader (DirectoryReader/open (fsdir index-dir))]
    (let [page-size        30
          searcher         (IndexSearcher. reader)
          ;parser           (QueryParser. "artifact-id" analyzer)
          ;query            (.parse parser query-str)
          ^Query query     (if (instance? Query query-in)
                             query-in
                             (parse-query query-in))
          ^TopDocs topdocs (.search searcher query page-size)
          hits             (.scoreDocs topdocs)]
      (f {:topdocs topdocs :score-docs hits :searcher searcher :query query}))))

(defn format-results
  "Format search results into what the frontend expects"
  [hits-cnt docs]
  {:count   hits-cnt
   :results (map
              (fn [{:keys [^Document doc score]}]
                {:artifact-id (.get doc "artifact-id")
                 :group-id    (.get doc "group-id")
                 :description (.get doc "description")
                 ;:origin (.get doc "origin")
                 ;; The first version appears to be the latest so this is OK:
                 :version (.get doc "versions")
                 :score       score})
              docs)})

(defn search [^String index-dir ^String query-in]
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

  (cljdoc.server.search.artifact-indexer/download-and-index! "data/index" :force)

  (search "data/index" "metosin muunta")
  ;; FIXME metosin:muuntaja comes 6th because the partial match on 'muuntaja' is
  ;; less worth than 1) double match on metosin (in a, g) and 2) match on g=metosin
  ;; and a random, rare artifact name => with high idf
  (explain-top-n 6 "data/index" "metosin muunta")

  (search "data/index" "re-frame")
  (search "data/index" "clojure")
  (search "data/index" "testit")
  (search "data/index" "ring")
  (search "data/index" "conc")
  (explain-top-n 3 "data/index" "clojure")

  nil)
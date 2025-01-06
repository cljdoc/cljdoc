(ns cljdoc.server.search.search
  "Index and search the Lucene index for artifacts best matching a query.

  See [[cljdoc.server.search.api]] for design goals.

  ## Troubleshooting and tuning tips ##

  * Use [[explain-top-n]] to get a detailed analysis of the score of the top N results for a query
  * Print out a Query - it's toString shows nicely what is it composed of, boosts, etc."
  (:require
   [babashka.fs :as fs]
   [cljdoc.server.clojars-stats :as clojars-stats]
   [cljdoc.server.search.clojars :as clojars]
   [cljdoc.server.search.maven-central :as maven-central]
   [clojure.string :as string]
   [clojure.tools.logging :as log])
  (:import
   #_(java.nio.file Paths)
   (java.util.concurrent Semaphore)
   (org.apache.lucene.analysis Analyzer Analyzer$TokenStreamComponents TokenStream Tokenizer)
   (org.apache.lucene.analysis.icu ICUFoldingFilter)
   (org.apache.lucene.analysis.ngram NGramTokenizer)
   (org.apache.lucene.analysis.tokenattributes CharTermAttribute)
   (org.apache.lucene.analysis.util CharTokenizer)
   (org.apache.lucene.document Document DoubleDocValuesField Field Field$Store FieldType StringField TextField)
   (org.apache.lucene.index DirectoryReader IndexOptions IndexReader IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode StoredFields Term)
   (org.apache.lucene.queries.function FunctionScoreQuery)
   (org.apache.lucene.search BooleanClause$Occur
                             BooleanQuery$Builder
                             BoostQuery
                             DoubleValuesSource
                             IndexSearcher
                             MatchAllDocsQuery
                             Query
                             ScoreDoc
                             TermQuery
                             TopDocs)
   (org.apache.lucene.search.similarities BM25Similarity)
   (org.apache.lucene.store Directory #_FSDirectory NIOFSDirectory)))

(set! *warn-on-reflection* true)

(defn- artifact->index-id ^String [{:keys [artifact-id group-id]}]
  (str group-id ":" artifact-id))

(defn- unsearchable-stored-field ^Field [^String name ^String value]
  (let [type (doto (FieldType.)
               (.setOmitNorms true)
               (.setIndexOptions IndexOptions/NONE)
               (.setTokenized false)
               (.setStored true)
               (.freeze))]
    (Field. name value type)))

(defn- add-versions [^Document doc versions]
  (run!
   #(.add doc
          (unsearchable-stored-field "versions" %))
   versions))

(defn- string-field
  "Return field that is:
  - indexed but not tokenized,
  - term freq. or positional info not indexed
  As far as I can tell, these are for exact matches."
  [^String name ^String value]
  (StringField. name value Field$Store/YES))

(defn- text-field
  "Return field that is indexed and tokenized."
  [^String name ^String value]
  (TextField. name value Field$Store/YES))

(defn- term
  "Return a lucene unit of search"
  [^String name ^String value]
  (Term. name value))

(defn- trim-inner-ws [s]
  (string/replace s #"\s+" " "))

(defn- first-sentence [s]
  (if (string/includes? s ". ")
    (string/replace s #"(.*?\.) .*" "$1")
    s))

(defn- truncate-at-word [s max-chars]
  (let [len (count s)]
    (if (<= len max-chars)
      s
      (if-let [sp-ndx (string/last-index-of s " " max-chars)]
        (subs s 0 sp-ndx)
        (subs s 0 max-chars)))))

(defn- description->blurb
  "Return blurb for description `s`.
  Strategy:
  - trim whitespace
  - then take first sentence if there seems to be one
  - then truncate to a maximum length of 200 chars respecting word boundary"
  [s]
  (let [max-chars 200]
    (-> s
        string/trim
        trim-inner-ws
        first-sentence
        (truncate-at-word max-chars))))

(defn- artifact->doc
  ^Iterable [{:keys [artifact-id
                     group-id
                     description
                     origin
                     versions]
              :as artifact}
             popularity]
  (doto (Document.)
    (.add (string-field "id" (artifact->index-id artifact))) ;; lucene unique id
    (cond-> origin
      (.add (string-field "origin" (name origin))))    ;; maven-central or clojars
    (.add (text-field "artifact-id" artifact-id))      ;; tokenized artifact-id
    (.add (text-field "group-id" group-id))            ;; tokenized group-id
    (.add (string-field "artifact-id.exact" artifact-id))
    (.add (string-field "group-id.exact" group-id))
    (cond-> description
      (.add (text-field "blurb" (description->blurb description)))) ;; tokenized jar pom.xml blurbified description
    (add-versions versions)
    (.add (DoubleDocValuesField. "popularity" popularity)) ;; 0 to 1 - popularity rating
    ;; we'll use this as our boost score at query time
    (.add (DoubleDocValuesField. "_score" (* 1.5 popularity)))))

(defn- clojars-download-boost [dl-max dl-lib]
  (if (or (nil? dl-max) (zero? dl-max))
    0 ;; if dl-max 0 or not available, we don't have stats yet.
    (/ dl-lib dl-max)))

(defn- calculate-doc-popularity
  "Return document's popularity relative to other documents.

  For clojars this is the artifact download count divided the download count for the most downloaded artifact.
  For maven we don't have download stats, so return ~1.0 for now."
  [clojars-stats {:as _jar :keys [origin artifact-id group-id]}]
  (case origin
    :maven-central (if (and (= "org.clojure" group-id) (= "tools.build" artifact-id))
                     0.99 ;; we want current io.github.clojure/tools.build to appear before legacy org.clojure/tools.build
                     1.0)
    :clojars (let [dl-max (clojars-stats/download-count-max clojars-stats)
                   dl-lib (clojars-stats/download-count-artifact clojars-stats group-id artifact-id)]
               (clojars-download-boost dl-max dl-lib))))

(defn- index-artifact [clojars-stats ^IndexWriter idx-writer artifact]
  (.updateDocument
   idx-writer
   (term "id" (artifact->index-id artifact))
   (artifact->doc artifact (calculate-doc-popularity clojars-stats artifact))))

(defn- track-indexing-status [{:keys [last-report-time] :as status}]
  (let [status (update status :artifacts-indexed inc)
        indexed (-> status :artifacts-indexed)
        report-every 1000]
    (if (= 0 (rem indexed report-every))
      (let [cur-time (System/currentTimeMillis)]
        (log/infof "Indexed %d artifacts (%.2f/second)" indexed (float (/ (* 1000 report-every) (- cur-time last-report-time))))
        (assoc status :last-report-time cur-time))
      status)))

(defn- custom-similarity
  "Return similarity (which affects ranking) that overrides some lucene defaults.

  From Lucene docs:

    BM25Similarity has two parameters that may be tuned:
    - k1, which calibrates term frequency saturation and must be positive or null.
      A value of 0 makes term frequency completely ignored, making documents scored only based on the value of the IDF of the matched terms.
      Higher values of k1 increase the impact of term frequency on the final score.
      Default value is 1.2.
    - b, which controls how much document length should normalize term frequency values and must be in [0, 1].
      A value of 0 disables length normalization completely.
      Default value is 0.75."
  []
  (let [k1 0 ;; term frequency unimportant
        b 0 ;; document length unimportant
        discount-overlaps true]
    (BM25Similarity. k1 b discount-overlaps)))

(defn- simple-tokenizer
  "Returns tokenizer suitable group-id and artifact-id and probably also pom descriptions.
  Effectively splits on punctuation or whitespace.
  No special attention paid to special characters at this time.
  Not sure if there is any benefit to splitting when alpha <-> numeric, so not bothering at this time (ex. lee42read will remain a single token)."
  ^Tokenizer []
  (proxy [CharTokenizer] []
    (isTokenChar [^long c]
      (Character/isLetterOrDigit c))))

(defn- ngram-tokenizer
  ^Tokenizer []
  (let [min-gram 1
        max-gram 80]
    (proxy [NGramTokenizer] [min-gram max-gram]
      (isTokenChar [^long c]
        (Character/isLetterOrDigit c)))))

(defn- artifact-analyzer
  "Returns analyzer for artifacts, `usage` must be:
  - `:search` for search time analysis
  - `:index` for index time analysis

  The only difference between the two is that at index time we employ the ngram tokenizer.
  The ngram filter will index token `yas` as `y`,`ya`,`yas`,`a`,`as`,`s` which
  supports substring matches at search time.

  BTW: We use an ngram tokenizer rather than an ngram filter to allow to allow lucene
  to store positions of each ngram in its index. This supports highlighting of fragments
  rather than entire tokens."
  ^Analyzer [usage]
  (proxy [Analyzer] []
    (createComponents [_fieldname]
      (let [tok (case usage
                  :search (simple-tokenizer)
                  :index (ngram-tokenizer))
            token-stream (ICUFoldingFilter. tok)]
        (Analyzer$TokenStreamComponents. tok token-stream)))))

;; public index fns

(def ^:private index-write-lock (Object.))

(defn index! [clojars-stats ^Directory index artifacts]
  ;; Lucene does not support multiple concurrent writers on a single index
  ;; for now, we take the simple approach and use a lock to avoid multiple concurrent writers
  (locking index-write-lock
    (let [analyzer (artifact-analyzer :index)
          iw-cfg   (doto (IndexWriterConfig. analyzer)
                     (.setOpenMode IndexWriterConfig$OpenMode/CREATE_OR_APPEND)
                     (.setSimilarity (custom-similarity)))]
      (with-open [idx-writer (IndexWriter. index iw-cfg)]
        (let [{:keys [artifacts-indexed start-time]}
              (reduce (fn [status artifact]
                        (try
                          (index-artifact clojars-stats idx-writer artifact)
                          (catch Exception e
                            (log/errorf e "Failed to index %s/%s" (:group-id artifact) (:artifact-id artifact))))
                        (track-indexing-status status))
                      {:artifacts-indexed 0
                       :last-report-time (System/currentTimeMillis)
                       :start-time (System/currentTimeMillis)}
                      artifacts)
              seconds-elapsed (float (/ (- (System/currentTimeMillis) start-time) 1000))]
          (log/infof "Artifact indexing complete. Indexed %d jars in %.2f seconds (%.2f/second)"
                     artifacts-indexed seconds-elapsed (/ artifacts-indexed seconds-elapsed)))))))

(defn make-index-reader-fn [^Directory index-directory]
  (let [^IndexReader reader (atom (DirectoryReader/open index-directory))
        refresh-semaphore (Semaphore. 1)]
    (fn index-reader-fn []
      (let [^DirectoryReader current-reader @reader]
        (if (.isCurrent current-reader)
          current-reader
          (if (.tryAcquire refresh-semaphore)
            (try
              (let [new-reader (DirectoryReader/openIfChanged current-reader)]
                (when new-reader
                  (swap! reader (constantly new-reader))
                  (.close current-reader))
                (.release refresh-semaphore)
                (or new-reader current-reader))
              (catch Exception e
                (.release refresh-semaphore)
                (throw e)))
            current-reader))))))

(defn index-reader-close [^IndexReader reader]
  (.close reader))

(defn download-and-index!
  "`:force-download? is to support testing at the REPL`"
  [clojars-stats ^Directory index & {:keys [force-download?]}]
  (log/info "Download & index starting...")
  (let [result (index! clojars-stats index (into (clojars/load-clojars-artifacts force-download?)
                                                 (maven-central/load-maven-central-artifacts)))]
    (log/info "Finished downloading & indexing artifacts.")
    result))

;; --- search support -----

(defn- tokenize
  "Return a sequence of String tokens for `text`."
  [^String text]
  (let [analyzer (artifact-analyzer :search)
        ^TokenStream stream (.tokenStream analyzer "fake-field-name" text)
        attr                (.addAttribute stream CharTermAttribute)]
    (.reset stream)
    (take-while
     identity
     (repeatedly
      #(when (.incrementToken stream) (str attr))))))

(defn- term-query ^Query [field ^String value]
  (-> field
      name
      (term value)
      (TermQuery.)))

(def ^:private boolean-ops {:should BooleanClause$Occur/SHOULD
                            :must BooleanClause$Occur/MUST})

(defn- boolean-query ^Query [op queries]
  (let [^BooleanQuery$Builder builder (->> queries
                                           (reduce (fn [^BooleanQuery$Builder builder q]
                                                     (.add builder q (op boolean-ops)))
                                                   (BooleanQuery$Builder.)))]
    (.build builder)))

(defn- boost ^Query [query val]
  (BoostQuery. query val))

(defn- exact-match-query [query-text]
  (let [query-text (string/trim query-text)
        terms (remove string/blank? (string/split query-text #"(\s+|/)"))
        cnt (count terms)]
    (case cnt
      1 (-> (boolean-query :should [(term-query :group-id.exact (first terms))
                                    (term-query :artifact-id.exact (first terms))])
            (boost 10.0))
      2 (-> (boolean-query :must [(term-query :group-id.exact (first terms))
                                  (term-query :artifact-id.exact (second terms))])
            (boost 10.0))
      nil)))

(defn- freetext-match-query [query-text]
  (let [tokens (tokenize query-text)
        clauses (map (fn [t] (boolean-query :should [(term-query :group-id t)
                                                     (term-query :artifact-id t)
                                                     (-> (term-query :blurb t) (boost 0.01))]))
                     tokens)]
    (if (next clauses)
      (boolean-query :must clauses)
      (first clauses))))

(defn- parse-query
  ^Query [query-text]
  (let [exact (exact-match-query query-text)
        free (freetext-match-query query-text)
        free (when free (FunctionScoreQuery/boostByValue free (DoubleValuesSource/fromDoubleField "_score")))
        queries (->> [exact free]
                     (keep identity)
                     (into []))]
    (when (seq queries)
      (boolean-query :should queries))))

(defn- hitdoc-num [^ScoreDoc scoredoc]
  (.-doc scoredoc))

(defn- hitdoc-score [^ScoreDoc scoredoc]
  (.score scoredoc))

(defn- total-hits
  "Return total hits for `topdocs`.
  This counts the number of hits and not the number of docs returned (ex. we asked for 10 but there were 800 hits).
  Lucene will only count accurately for up to 1000 hits, but that is fine for our usage."
  [^TopDocs topdocs]
  (-> topdocs (.-totalHits) (.value)))

(defn- matched-doc ^Document [^IndexSearcher searcher ^ScoreDoc scoredoc]
  (let [^StoredFields stored-fields (.storedFields searcher)]
    (.document stored-fields (hitdoc-num scoredoc))))

(defn- doc->artifact [^Document doc score]
  {:group-id (.get doc "group-id")
   :artifact-id (.get doc "artifact-id")
   :blurb (.get doc "blurb")
   :origin (.get doc "origin")
   :versions (.getValues doc "versions")
   :score score})

(defn- search->results*
  [^IndexReader index-reader ^Query query ^long max-results]
  (let [^long max-results' (if (zero? max-results)
                             (.maxDoc index-reader)
                             max-results)
        searcher (doto (IndexSearcher. index-reader)
                   (.setSimilarity (custom-similarity)))
        topdocs (.search searcher query max-results')]
    {:topdocs topdocs :searcher searcher}))

(defn- search->results
  "Return docs matching `query-in` in `index` formatted with function `f`.
  Result keys:
  - :count - total hits
  - :results - vector of matching artifacts with :score"
  ([index-reader-fn query]
   (search->results index-reader-fn query 30))
  ([index-reader-fn ^Query query ^long max-results]
   (if query
     (let [reader (index-reader-fn)
           {:keys [^TopDocs topdocs searcher]}
           (search->results* reader query max-results)]
       {:count (total-hits topdocs)
        :results (->> (.scoreDocs topdocs)
                      (mapv (fn [^ScoreDoc scoredoc]
                              (doc->artifact (matched-doc searcher scoredoc)
                                             (hitdoc-score scoredoc)))))})
     {:count 0 :results []})))

(defn- snapshot? [version]
  (string/ends-with? version "-SNAPSHOT"))

;; public general purpose fns

(defn disk-index [index-path]
  ;; temporarily explore mysterious native memory usage, is Lucene contributing?
  (NIOFSDirectory. (fs/path index-path))
  #_(FSDirectory/open ;; automatically chooses best implementation for OS
     (Paths/get index-path (into-array String nil))))

(defn index-close [^Directory index]
  (.close index))

;; public search fns

(defn search
  "Search lucene `index` for `query-in` text as typed by user."
  [index-reader-fn ^String query-in]
  (let [r (search->results index-reader-fn (parse-query query-in))]
    (assoc r :results
           (reduce
            (fn [acc {:keys [versions] :as artifact}]
              (let
               [latest-version (first versions)
                [release-version snapshot-version] (if (snapshot? latest-version)
                                                     [(some #(when (not (snapshot? %)) %) versions) latest-version]
                                                     [latest-version nil])
                lib (select-keys artifact [:artifact-id :group-id :blurb :origin :score])]
                (cond-> acc
                  release-version (conj (assoc lib :version release-version))
                  snapshot-version (conj (assoc lib :version snapshot-version)))))
            []
            (:results r)))))

(defn versions
  "Returns artifacts and their non-SNAPSHOT versions.
  Result sort order is indeterminate.
  By default returns all artifacts, can optionally specify:
  - group-id - return all matches for exact match on group id
  - artifact-id - return all matches for exact match on artifact-id under group-id (ignored if group-id not specified)"
  [index-reader-fn {:keys [group-id artifact-id]}]
  (->> (cond (and group-id artifact-id)
             (search->results index-reader-fn (boolean-query :must [(term-query :group-id.exact group-id)
                                                                    (term-query :artifact-id.exact artifact-id)]))
             group-id
             (search->results index-reader-fn (term-query :group-id.exact group-id))
             :else
             (search->results index-reader-fn (MatchAllDocsQuery.) 0))
       :results
       (mapv (fn [artifact]
               (-> artifact
                   (select-keys [:group-id :artifact-id])
                   (assoc :versions (->> artifact
                                         :versions
                                         (remove #(string/ends-with? % "-SNAPSHOT"))
                                         (into []))))))))

(defn suggest
  "Returns [`query-in` [project1 project2... project5]] for `query-in`.

   For OpenSearch as-you-type suggest support used in web browsers.

   Note: In Firefox, the response needs to reach the browser within 500ms otherwise it will be discarded.
   For more information, see:
   - https://github.com/dewitt/opensearch/blob/master/mediawiki/Specifications/OpenSearch/Extensions/Suggestions/1.1/Draft%201.wiki
   - https://developer.mozilla.org/en-US/docs/Web/OpenSearch
   - https://developer.mozilla.org/en-US/docs/Archive/Add-ons/Supporting_search_suggestions_in_search_plugins"
  [index-reader-fn query-in]
  (let [suggestions (->> (search->results index-reader-fn (parse-query query-in) 5)
                         :results
                         (mapv (fn [{:keys [group-id artifact-id]}]
                                 (str group-id "/" artifact-id
                                      ;; without this trailing space browsers (Firefox for one)
                                      ;; will skip the suggestion because it thinks the / char makes
                                      ;; it looks like a URL
                                      " "))))]
    [query-in suggestions]))

;; REPL exploratory support

#_{:clj-kondo/ignore [:clojure-lsp/unused-public-var]}
(defn explain-top-n
  "Debugging function to print out the score and its explanation of the
   top `n` matches for the given query."
  ([index query-in] (explain-top-n 5 index query-in))
  ([n ^Directory index query-in]
   (with-open [^IndexReader reader (DirectoryReader/open index)]
     (let [query (parse-query query-in)
           {:keys [^TopDocs topdocs ^IndexSearcher searcher]} (search->results* reader query n)]
       (println query)
       (run!
        (fn [^ScoreDoc score-doc]
          (let [^StoredFields stored-fields (.storedFields searcher)]
            (println (.get (.document stored-fields (.-doc score-doc)) "id")
                     (.explain searcher query (.-doc score-doc)))))
        (take n (.scoreDocs topdocs)))))))

(comment

  (exact-match-query "hello-billy/there")
  (exact-match-query "hello")

  (freetext-match-query "hello")
  (parse-query "hello billy boy")

  (exact-match-query "q")

  (freetext-match-query "")

  (parse-query "q")
  (parse-query "")
  (parse-query "   ")

  (def clojars-stats (:cljdoc/clojars-stats integrant.repl.state/system))

  (def index (disk-index "data/index-lucene-10_0_0"))

  (def index-reader-fn (make-index-reader-fn index))

  (download-and-index! clojars-stats
                       index
                       :force-download? true)

  (search index-reader-fn "metosin muunta")

  (explain-top-n 6 index "metosin muunta")

  (search index-reader-fn "re-frame")
  (search index-reader-fn "org.clojure")
  (search index-reader-fn "testit")
  (search index-reader-fn "ring")
  (search index-reader-fn "conc")
  (search index-reader-fn "facade")

  (.toString (parse-query "q/q"))
  ;; => "FunctionScoreQuery((+group-id.exact:q +artifact-id.exact:q)^100.0 (+(group-id:q artifact-id:q (blurb:q)^0.01) +(group-id:q artifact-id:q (blurb:q)^0.01)), scored by boost(double(_score)))"

  (search index-reader-fn "q/q")
  (explain-top-n 3 index "q/q")

  (search index-reader-fn "")

  (suggest index-reader-fn "clojure tools")
  (suggest index-reader-fn "nothingatall")

  (search index-reader-fn "clj-kondo")

  (explain-top-n 3 index "clojure")

  (tokenize "façade");; => ("facade")
  (tokenize "XL---42+'Autocoder billy");; => ("xl" "42" "autocoder" "billy")
  (tokenize "hEy42-the⓰re.slugger.");; => ("hey42" "the" "re" "slugger")
  (tokenize "com.github.lread/test-doc-blocks");; => ("com" "github" "lread" "test" "doc" "blocks")

  nil)

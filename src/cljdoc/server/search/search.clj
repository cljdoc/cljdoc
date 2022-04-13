(ns cljdoc.server.search.search
  "Index and search the Lucene index for artifacts best matching a query.

  ## Troubleshooting and tuning tips ##

  * Use `explain-top-n` to get a detailed analysis of the score of the top N results for a query
  * Print out a Query - it's toString shows nicely what is it composed of, boosts, etc.
  "
  (:require
   [cljdoc.server.search.clojars :as clojars]
   [cljdoc.server.search.maven-central :as maven-central]
   [cljdoc.spec :as cljdoc-spec]
   [clojure.spec.alpha :as s]
   [clojure.string :as string]
   [clojure.tools.logging :as log])
  (:import
   (java.nio.file Paths)
   (org.apache.lucene.analysis CharArraySet TokenStream)
   (org.apache.lucene.analysis.core StopAnalyzer)
   (org.apache.lucene.analysis.miscellaneous PerFieldAnalyzerWrapper)
   (org.apache.lucene.analysis.standard StandardAnalyzer)
   (org.apache.lucene.document Document Field FieldType Field$Store StringField TextField)
   (org.apache.lucene.index DirectoryReader IndexOptions IndexWriter IndexWriterConfig IndexWriterConfig$OpenMode Term)
   (org.apache.lucene.search BooleanQuery$Builder BooleanClause$Occur BoostQuery IndexSearcher MatchAllDocsQuery PrefixQuery Query ScoreDoc TermQuery TopDocs)
   (org.apache.lucene.store FSDirectory)))

(defn ^FSDirectory fsdir [index-dir]
  (FSDirectory/open (Paths/get index-dir (into-array String nil))))

(defn ^String artifact->id [{:keys [artifact-id group-id]}]
  (str group-id ":" artifact-id))

(defn unsearchable-stored-field [^String name ^String value]
  (let [type (doto (FieldType.)
               (.setOmitNorms true)
               (.setIndexOptions (IndexOptions/NONE))
               (.setTokenized false)
               (.setStored true)
               (.freeze))]
    (Field.
     name
     value
     type)))

(defn add-versions [doc versions]
  (run!
   #(.add doc
          (unsearchable-stored-field "versions" %))
   versions))

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

(defn ^Iterable artifact->doc
  [{:keys [^String artifact-id
           ^String group-id
           ^String description
           ^String origin
           versions]
    :or {description "", origin "N/A"}
    :as artifact}]
  (doto (Document.)
    ;; *StringField* is indexed but not tokenized, term freq. or positional info not indexed
    ;; id: We need a unique identifier for each doc so that we can use updateDocument
    (.add (StringField. "id" (artifact->id artifact) Field$Store/YES))
    (.add (StringField. "origin" ^String (name origin) Field$Store/YES))
    (.add (TextField. "artifact-id" artifact-id Field$Store/YES))
    ;; Keep also un-tokenized version of the id for RegExp searches (Better to replace with
    ;; a custom tokenizer that produces both the original + individual tokens)
    (.add (StringField. "artifact-id-raw" artifact-id Field$Store/YES))
    (.add (TextField. "group-id" group-id Field$Store/YES))
    (.add (TextField. "group-id-packages" group-id Field$Store/YES))
    (.add (StringField. "group-id-raw" group-id Field$Store/YES))
    (cond-> description
      (.add (TextField. "blurb" (description->blurb description) Field$Store/YES)))
    (add-versions versions)))

(defn index-artifacts [^IndexWriter idx-writer artifacts create?]
  (run!
   (fn [artifact]
     (if create?
       (.addDocument idx-writer (artifact->doc artifact))
       (.updateDocument
        idx-writer
        (Term. "id" (artifact->id artifact))
        (artifact->doc artifact))))

   artifacts))

(defn mk-indexing-analyzer []
  (PerFieldAnalyzerWrapper.
   (StandardAnalyzer.)
    ;; StandardAnalyzer does not break at . as in 'org.clojure':
   {"group-id"          (StopAnalyzer. (CharArraySet. ["." "-"] true))
     ;; Group ID with token per package component, not breaking at '-' this
     ;; time so that 'clojure' will match 'org.clojure' better than 'org-clojure':
    "group-id-packages" (StopAnalyzer. (CharArraySet. ["."] true))}))

(defn index! [^String index-dir artifacts]
  (let [create?   false ;; => update, see how we use `.updateDocument` above
        analyzer (mk-indexing-analyzer)
        iw-cfg   (doto (IndexWriterConfig. analyzer)
                   (.setOpenMode (if create?
                                   IndexWriterConfig$OpenMode/CREATE
                                   IndexWriterConfig$OpenMode/CREATE_OR_APPEND))
                   ;; Increase RAM for speed up. BEWARE: Increase also JVM heap size: -Xmx512m or -Xmx1g
                   (.setRAMBufferSizeMB 256.0))
        idx-dir  (fsdir index-dir)]
    (with-open [idx-writer (IndexWriter. idx-dir iw-cfg)]
      (index-artifacts
       idx-writer
       artifacts
       create?))))

(defn download-and-index!
  ([^String index-dir] (download-and-index! index-dir false))
  ([^String index-dir force?]
   (log/info "Download & index starting...")
   (let [result (index! index-dir (into
                                   (clojars/load-clojars-artifacts force?)
                                   (maven-central/load-maven-central-artifacts force?)))]
     (log/info "Finished downloading & indexing.")
     result)))

(defn index-artifact [^String index-dir artifact]
  (index! index-dir [artifact]))

(s/fdef index-artifact
  :args (s/cat :index-dir string? :artifact ::cljdoc-spec/artifact))

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

(def search-fields [:artifact-id :group-id :group-id-packages :blurb])

(def boosts
  "Boosts for selected artifact fields (artifact id > group id > blurb
   The numbers were determined somewhat randomly but seem to work well enough."
  {:artifact-id       3
   :group-id-packages 3
   :group-id          2
   :blurb             1})

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
      (PrefixQuery. t)
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
    (->> (map
          #(token->query %1 %2 (when multitoken? query-text))
          tokens match-modes)
         (apply boolean-query BooleanClause$Occur/MUST))))

(defn search->results
  "NOTE: We take the result formatting function `f` as a parameter because it
   must be run within the context of our `with-open`."
  ([^String index-dir ^String query-in f]
   (search->results index-dir query-in 30 f))
  ([^String index-dir ^String query-in ^long max-results f]
   (with-open [reader (DirectoryReader/open (fsdir index-dir))]
     (let [^int max-results' (if (zero? max-results)
                               (.maxDoc reader)
                               max-results)
           searcher         (IndexSearcher. reader)
           ;parser           (QueryParser. "artifact-id" analyzer)
           ;query            (.parse parser query-str)
           ^Query query     (if (instance? Query query-in)
                              query-in
                              (parse-query query-in))
           ^TopDocs topdocs (.search searcher query max-results')
           hits             (.scoreDocs topdocs)]
       (f {:topdocs topdocs :score-docs hits :searcher searcher :query query})))))

(defn index-version
  "Version of the index content, updated every time the index is changed."
  [^String index-dir]
  (.getVersion (DirectoryReader/open (fsdir index-dir))))

(defn- snapshot? [version]
  (string/ends-with? version "-SNAPSHOT"))

(defn format-results
  "Format search results into what the frontend expects.
  One lib can return two rows if both a release and snapshot versions exist.
  Snapshot version only returned if greater than current release version (or if there are no release versions)."
  [hits-cnt docs]
  {:count   hits-cnt
   :results (reduce
             (fn [acc {:keys [^Document doc score]}]
               (let
                [versions (.getValues doc "versions")
                 latest-version (first versions)
                 [release-version snapshot-version] (if (snapshot? latest-version)
                                                      [(some #(when (not (snapshot? %)) %) versions) latest-version]
                                                      [latest-version nil])
                 lib {:artifact-id (.get doc "artifact-id")
                      :group-id    (.get doc "group-id")
                      :blurb       (.get doc "blurb")
                      :origin      (.get doc "origin")
                      :score       score}]
                 (cond-> acc
                   release-version (conj (assoc lib :version release-version))
                   snapshot-version (conj (assoc lib :version snapshot-version)))))
             []
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

(defonce all-docs-cache (atom nil)) ;; As of 11/2019 it takes ± 2.6MB (well, when stringified)

;; TODO Add variants fetching versions for a group / a group+artifact => presumabely faster, no need for caching as all-docs will be needed rarely
;; (If we still want to preserve its caching - Move the state into the ISearcher impl so that integrant can manage and restart it properly)
(defn all-docs
  "Returns all the documents in the Lucene index, with all the versions.
  NOTE: Takes a few seconds."
  [^String index-dir]
  (let [idx-version (index-version index-dir)
        [cached-version cached-docs] @all-docs-cache]
    (if (= idx-version cached-version)
      cached-docs
      (let [docs (search->results
                  index-dir
                  (MatchAllDocsQuery.)
                  0 ; = "all"
                  (fn [{docs :score-docs, searcher :searcher}]
                    (mapv ;; non-lazy
                     (fn [^ScoreDoc sdoc]
                       (let [doc (.doc searcher (.-doc sdoc))]
                         {:artifact-id (.get doc "artifact-id")
                          :group-id    (.get doc "group-id")
                          ;; The first version appears to be the latest so this is OK:
                          :versions (->> (.getValues doc "versions")
                                         (remove #(string/ends-with? % "-SNAPSHOT"))
                                         (into []))}))

                     docs)))]
        (reset! all-docs-cache [idx-version docs])
        docs))))

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
                     (.get doc "artifact-id")
                     ;; without this trailing space browsers (Firefox for one)
                     ;; will skip the suggestion because it thinks the / char makes
                     ;; it looks like a URL
                     " "))))))]
    [query-in suggestions]))

(defn explain-top-n
  "Debugging function to print out the score and its explanation of the
   top `n` matches for the given query."
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

  (download-and-index! "data/index-lucene91" :force)

  (search "data/index-lucene91" "metosin muunta")
  ;; FIXME metosin:muuntaja comes 6th because the partial match on 'muuntaja' is
  ;; less worth than 1) double match on metosin (in a, g) and 2) match on g=metosin
  ;; and a random, rare artifact name => with high idf
  (explain-top-n 6 "data/index-lucene91" "metosin muunta")

  (all-docs "data/index-lucene91")

  (search "data/index-lucene91" "re-frame")
  (search "data/index-lucene91" "clojure")
  (search "data/index-lucene91" "testit")
  (search "data/index-lucene91" "ring")
  (search "data/index-lucene91" "conc")

  (search "data/index-lucene91" "clj-kondo")

  (explain-top-n 3 "data/index-lucene91" "clojure")

  nil)

(ns cljdoc.server.search.api
  "Index and search Maven/Clojars artifacts.

  Requirements:
   1. Prioritize artifact id >= group id > description.
   2. Boost if match both in artifact id and group id, such as in 're-frame:re-frame'.
   3. Boost the shortest match.
   4. Prefix search - not necessary to type the whole name, e.g. 'concord' -> 'concordia'.
   5. Word search - match even just a single word from the name, e.g. 'http' -> 'clj-http' for artifact-id
     and 'nervous' -> 'io.nervous' from the group-id. But match the whole thing too, e.g.
     'clj-nyam' -> 1. 'clj-nyam' 2. 'nyam'. Especially important for names like 're-frame' where - is an
     integral part of the name.
   6. Boost by download count so that if multiple artifacts with similar score, the most popular ones come first.
   7. (?) Most similar match when no direct one - if I search for 'clj-http2' but there is no such package, only 'http2',
      I want to get 'http2' back despite the fact there is no 'clj' in it.

  Examples:
   conc -> clj-concordion
   ring -> ring:ring-core
   clojure -> org.clojure:clojure, org.clojure:clojurescript
   nyam -> clj-nyam
   re-frame -> re-frame with re-frame:re-frame first (even if disregarding download count)
   frame -> re-frame:re-frame
   RiNg -> ring
   nervous ->io.nervous:* artifacts

  Decisions:
   1. Search each field individually so that we can boost artifact-id > group-id > description.
   2. Don't use RegexpQuery by default, i.e. 'clj-http' -> '.*clj-http.*' because:
      a) I'm not sure whether it is good w.r.t. our scoring preferences - prefer the shortest match,
         prefer to match at word start rather than its middle
      b) Tokenizing the names and description makes it possible to find similar artifacts when no direct match.
      c) Prepending '.*' (so we can match 'http' -> 'clj-http') makes the query slow (and we run it on 3 fields);
         but maybe not relevant with our data set size?
   3. Break names at '.', '-' so that we can search for their meaningful parts, see #5 above.
   4. Use PrefixQuery so that the whole name does not need to be typed.
   5. Add boost to artifact id then group id to support req. 1.
   6. Include '-raw' fields for group, artifact so that we can boost exact matches.
  "
  (:require
    [cljdoc.server.search.search :as search]
    [cljdoc.server.search.artifact-indexer :as indexer]
    [tea-time.core :as tt]
    [clojure.spec.alpha :as s]
    [clojure.tools.logging :as log]
    [integrant.core :as ig])
  (:import (java.util.concurrent TimeUnit)))

(defprotocol ISearcher
  ;; We use a protocol so that we can create once the datatype implementing it and
  ;; wrap the required configuration in it, passing the type instead of the raw config
  ;; to each of the functions that need it.
  "Index and search artifacts."
  (index-artifact [_ artifact])
  (search [_ query])
  (suggest [_ query]))

(defrecord Searcher [index-dir]
  ISearcher
  (index-artifact [_ artifact]
    (indexer/index-artifact index-dir artifact))
  (search [_ query]
    (search/search index-dir query))
  (suggest [_ query]
    (search/suggest index-dir query)))

(defmethod ig/init-key :cljdoc/searcher [_ {:keys [index-dir enable-indexer? db-spec] :or {enable-indexer? true}}]
  (map->Searcher {:index-dir        index-dir
                  :artifact-indexer (when enable-indexer?
                                      (log/info "Starting ArtifactIndexer")
                                      (tt/every! (.toSeconds TimeUnit/HOURS 1) #(indexer/download-and-index! index-dir db-spec)))}))

(defmethod ig/halt-key! :cljdoc/searcher [_ searcher]
  (when-let [indexer (:artifact-indexer searcher)]
    (log/info "Stopping ArtifactIndexer")
    (tt/cancel! indexer)))

(comment

  (def sr (ig/init-key :cljdoc/searcher {:index-dir "data/index"}))
  (ig/halt-key! :cljdoc/searcher sr)

  nil)
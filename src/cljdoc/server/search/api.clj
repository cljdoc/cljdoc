(ns cljdoc.server.search.api
  "Index and search Maven/Clojars artifacts.

   Design goals:
   1. Prefer matches in artifact id and group id over blurb.
   2. Prefer an exact match in artifact id and/or group id over partial matches
   3. Match anywhere in text, ex. `corfield` matches `seancorfield`, `cow` matches `scowl`, etc.
   4. Boost by clojars download count so that if multiple artifacts with similar score, the most popular ones come first.

  Examples:
   conco -> clj-concordion
   ring -> ring
   org.clojure -> org.clojure:clojure, org.clojure:clojurescript
   nyam -> clj-nyam
   re-frame -> re-frame with re-frame:re-frame first
   frame -> licaltown/frame (because exact match weighs heavy) then later re-frame:re-frame
   RiNg -> ring
   RèWRïTÉ -> rewrite-clj
   nervous ->io.nervous:* artifacts"
  (:require
   [cljdoc.server.search.search :as search]
   [clojure.tools.logging :as log]
   [integrant.core :as ig]
   [tea-time.core :as tt])
  (:import (java.util.concurrent TimeUnit)
           (org.apache.lucene.store Directory)))

(defprotocol ISearcher
  ;; We use a protocol so that we can create once the datatype implementing it and
  ;; wrap the required configuration in it, passing the type instead of the raw config
  ;; to each of the functions that need it.
  "Index and search artifacts."
  (artifact-versions [_ refined-by] "Return all artifact versions, optionally refined-by a group-id or a group-id and artifact-id.")
  (index-artifacts [_ artifacts])
  (search [_ query] "Supports web app libraries search")
  (suggest [_ query] "Supports OpenSearch libraries suggest search."))

(defrecord Searcher [clojars-stats ^Directory index index-reader-fn]
  ISearcher
  (artifact-versions [_ refined-by]
    (search/versions index-reader-fn refined-by))
  (index-artifacts [_ artifacts]
    (search/index! clojars-stats index artifacts "newly discovered artifacts"))
  (search [_ query]
    (search/search index-reader-fn query))
  (suggest [_ query]
    (search/suggest index-reader-fn query)))

(defmethod ig/init-key :cljdoc/searcher [k {:keys [clojars-stats index-factory index-dir enable-indexer?]
                                            :or {enable-indexer? true}}]
  (log/info "Starting" k)
  (let [index (if index-factory ;; to support unit testing
                (index-factory)
                (search/disk-index index-dir))]
    ;; Force creation of initial index. It might not exist yet, for example, when upgrading to a new version of lucene.
    ;; This avoids exceptions on searching a non-existing index.
    (search/index! clojars-stats index [] "index initialization")
    (map->Searcher {:index index
                    :index-reader-fn (search/make-index-reader-fn index)
                    :clojars-stats clojars-stats
                    :clojars-artifact-indexer (when enable-indexer?
                                                (log/info "Starting ArtifactIndexer for clojars")
                                                (tt/every! (.toSeconds TimeUnit/HOURS 1)
                                                           #(search/download-and-index! clojars-stats index {:origin :clojars})))
                    :maven-central-artifact-indexer (when enable-indexer?
                                                      (log/info "Starting ArtifactIndexer for maven-central")
                                                      (tt/every! (.toSeconds TimeUnit/HOURS 1)
                                                                 #(search/download-and-index! clojars-stats index {:origin :maven-central})))})))

(defmethod ig/halt-key! :cljdoc/searcher
  [k {:keys [clojars-artifact-indexer maven-central-artifact-indexer index index-reader-fn] :as _searcher}]
  (log/info "Stopping" k)
  (when clojars-artifact-indexer
    (tt/cancel! clojars-artifact-indexer))
  (when maven-central-artifact-indexer
    (tt/cancel! clojars-artifact-indexer))
  (when-let [index-reader (index-reader-fn)]
    (search/index-reader-close index-reader))
  (when index
    (search/index-close index)))

(comment
  (def sr (ig/init-key :cljdoc/searcher {:index-dir "data/index"}))
  (ig/halt-key! :cljdoc/searcher sr)

  nil)

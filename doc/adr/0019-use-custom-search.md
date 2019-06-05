# Implement our own indexing and artifacts search

## Status

Proposed

## Context

We need to be able to search (documented) projects, whether 
they come from Clojars or Maven Central.

See https://github.com/cljdoc/cljdoc/issues/85

## Decision

Implement our own search, using direct integration with Lucene. Download and 
index artifact list from Clojars and "org.clojure" artifacts from Maven Central.

We will use Lucene as that is the absolutely prevailing solution for search in
Java. Direct Java interop is quite idiomatic in Clojure; it isn't too much work as we
only need to implement the parts relevant for us and not a generic Lucene wrapper.
We avoid the risk of depending on incomplete and potentially abandoned library
(as happend to Clojars with clucy). And to be able to use Lucene efficiently we
need to understand it sufficiently anyway.

## Consequences

* Our search results will be different from those you get from Clojars, we won't
  benefit from any improvements on Clojars' side
* We will be able to search also for non-Clojars projects (and thus also make 
  their docset available in Dash)
* Cljdoc will become slightly more complex and possibly expensive to operate 
  (due to downloading the ~10MB index from Clojars regularly)
* New projects will not appear immediately in the search until the next scheduled
  indexing (unless we mitigate that somehow)
* We can fine-tune the search to prioritize the results we want
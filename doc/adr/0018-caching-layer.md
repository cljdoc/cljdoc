# A caching layer based on memoized functions backed by sqlite

## Status

Proposed

## Context
Cljdoc needs to store ad-hoc data that doesn't directly fit in its data model.
For example: Project's download stats on Clojars, Projects contributors, stars on Github etc.
We need this data to show alongside project's documentation. The source of truth for this kind of data is a service across the network.
Clojars, Github, Maven Central etc. Making multiple network calls to render documentation for a projects deteriorates performance.

## Decision
We decide to cache this data to avoid multiple network calls. We are using `clojure.core.memoize` to provide a layer over this cache.
It enables us to memoize functions that make network calls. `clojure.core.memoize` requires an implementation of `clojure.core.cache/CacheProtocol`
to memoize return values of a function. We provide an implementation of this protocol. Our cache has two important properties.
First, it is persistent (backed by cljdoc datastore), we don't want to loose cached data on server restart.
Second, it has a TTL functionality. Some data such as Github stars and Clojars download count etc should be refreshed timely.
TTL can be used to set expiry time for a cached item.

## Consequences

##### Performance:
By avoiding multiple network calls to render a documentation we reduce the page load latency.

##### Debugging:
Memoized functions may return data from the original data source or from cache when there is a cache hit.
For debugging purpose sometimes it could be challenging to trace where the data is coming from.

##### Security:
This cache implementation is backed by sqlite to provide persistence. It runs some raw SQL queries that are prone to sql injection attacks.
As a safety measure any value that is received from the client should not be used as a parameter to initialize this cache.

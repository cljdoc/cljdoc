# 5. Implement Minimal API Abstracting Grimoire

Date: 2018-01-14

## Status

Accepted

## Context

Grimoire is great as a single source of truth due do it's well designed data-model. The APIs Grimoire exposes have the following drawbacks however:

- Clojure specific
  - other languages will need to reimplement storage abstractions, which is tedious work and bug-prone
- Slightly unwieldy for the use case that seems most common for tooling authors that want to build on top of Grimoire data:
  - "Give me all information about an `artifact`" (namespaces, defs, version, etc.)
  - `artifact` could be something like `[bidi "2.1.3"]` here

## Decision

In order to make Grimoire data as easy to use as possible from a wide variety of languages we will implement a minimal "cache" API on top.

This API will have the following properties:
- file-based (single files which contain different "bundles" of Grimoire data)
- files are encoded in [Transit](https://github.com/cognitect/transit-format) to reduce cost of duplicate strings and similar
- files can be regenerated at any time

An initial implementation of this cache can be fund in `cljdoc.cache` (as of
`fc3c6b2`). A spec has been written in `cljdoc.spec`. The cache follows the basic form of

```clojure
{:cache-id       {,,,}
 :cache-contents {,,,}}
```

> Cache storage could also be implemented as a storage implementation for
Grimoire. Benefit would be that all Grimoire operations are supported. Given
that we want to expose an API with only a very small subset of Grimoire's and do that for multiple languages the utility of this is not immediately apparent.

## Consequences

The cache map may be extended in the future. Especially a timestamp about
when it was generated could be useful to validate that a bundle has been
regenerated at a certain point in time.

**Since cache-map generation and structure may change over the course of time
and also will become a primary API some versioning should also be considered.**
This could be as simple as an additional field `:cache-format 1`, where `1`
gets incremented any time the cache format changes.




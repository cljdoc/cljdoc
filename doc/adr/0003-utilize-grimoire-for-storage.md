# 3. Utilize Grimoire for storage

Date: 2018-01-12

## Status

Accepted

## Context

We need to store documentation (API, articles, etc.) for many different projects, versions
and platforms.

## Decision

[Grimoire](https://github.com/clojure-grimoire/lib-grimoire) has a storage design that
supports those requirements and seems to be a well designed project overall.

Grimoire also supports implementing different storage backends which may be useful later.

For now we will try to build a filesystem based storage for documentaton based on Grimoire's
format and ideas.

## Consequences

Use Grimoire, don't try to come up with alternative storage designs.

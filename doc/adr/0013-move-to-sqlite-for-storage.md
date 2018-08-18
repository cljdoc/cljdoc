# 13. Move to SQLite for Storage

Date: 2018-08-02

## Status

Accepted 

## Context

From the start cljdoc embraced a library called [`lib-grimoire`](https://github.com/clojure-grimoire/lib-grimoire), which is effectively a filesystem-backed database to store information about Clojure code (namespaces, vars, etc.). Having this as foundation has allowed cljdoc to focus on automation and UI work with are much more visible/impactful especially at an early stage.

Now cljdoc has seen some adoption and more features are coming up (Spec integration, examples) that don't directly fit `lib-grimoire`'s data model. With `lib-grimoire` being built on top of basic filesystem features (files and directories) it might be hard to extend it to accompany this additional data.

To recap: cljdoc's data model in it's current form pretty much follows this hierarchy: 

**Groups → Artifacts → Versions → Namespaces → Vars**

All of these are represented as maps and stored as `.edn` files.

## Decision

**Move data out of Grimoire into SQLite.**

SQLite provides low operational complexity while still providing many of the benefits a proper SQL database would provide. These benefits include:

- a more flexible schema
- generic query language allowing the domain model to evolve regardless of Grimoire's future development
- potentially better performance

In initial testing SQLite performed typical operations up to 15x faster than the filesystem-backed Grimoire store and performance did not degrade with more data being inserted.

Using SQLite will also allow a transition to Postgres (or similar) with relative ease if/when that becomes necessary.

To keep things as close to Grimoire as possible for now we won't   try to put all keys of our entity maps into individual columns but instead just store most data as BLOBs. Some examples of what goes into those blobs:

- For **Version** entities: 
	- the doctree, i.e. all articles (like Readme & Changelog)
	- a list of files in the project's Git repository and their respective SHAs
	- metadata on the Git revision for that version (SHA, tag, etc.)
- For **Namespace** entities:
	- docstring
- For **Vars/Defs** entities:
	- docstring
	- arglists
	- type (macro, var, protocol, etc.)

> **Notes:** 
> 
> - This list is non-exhaustive. 
> - The terms "vars" and "defs" are sometimes used interchangeably.
> - The blobs will be serialised using [nippy](https://github.com/ptaoussanis/nippy). 

Fields relevant for querying or data-validity are moved into proper columns. For instance for vars we want to ensure there is only one var with the same `(platform, namespace, name)` and so these fields are put into designated columns. 

## Consequences

Preparations for this have already begun with [issue 58](https://github.com/cljdoc/cljdoc/issues/58).

Once the necessary changes are in place a one-off migration will be necessary to transfer the data from one data store to the other.

Alternatively it could be tried to run both storage backends at the same time and intelligently delegate but it seems that this approach would be better suited when high availability is critical. 

## Appendix

#### SQLite Performance Notes

- API data of Amazonia is a good test case because it's a lot namespaces/vars.
- https://stackoverflow.com/a/6533930 pragmas

```
PRAGMA main.page_size = 4096;
PRAGMA main.synchronous=NORMAL;
PRAGMA main.journal_mode=WAL;
PRAGMA main.cache_size=10000; 
```


- Various benchmarks with hibernate: http://www.jpab.org/Hibernate/HSQLDB/embedded/Hibernate/SQLite/embedded.html
- https://github.com/JoshuaWise/better-sqlite3/wiki/Performance
- [PRAGMA documentation](https://www.sqlite.org/pragma.html)

#### Thoughts on Non-Maven Origins (GitHub etc.)

> This is not exactly related to this change in the cljdoc persistence layer but still worth thinking about.

Currently all artifacts cljdoc provides documentation for are in some maven repository — but we may want to expand do other sources (GitHub via tools.deps or similar).

This may require additional namespacing as otherwise there might be conflicts between, e.g. GitHub and Clojars. 

#### Why not X?

> This isn't intended to be a full analysis but just some basic notes.

**Postgres:** SQLite gives us the same basic abstractions, reasonable
performance for our current usage patterns while being significantly
easier to operate.

**Datomic:** Datomic is aimed at domains where time is an important
component, which is not the case for cljdoc. Except for the H2 variant
it has the same operational complexity as Postgres. H2's performance
degraded when testing with the approach (blobs and all) described above.

While Clojure people might be familiar with Datomic it's probably fair
to assume that more people are familiar with SQL. This is important if
we try to optimize for a diverse crowd of contributors.

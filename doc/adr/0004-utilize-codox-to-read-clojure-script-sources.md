# 4. Utilize Codox to Read Clojure/Script Sources

Date: 2018-01-14

## Status

Accepted

Supercedes [2. Don't build on top of Codox](0002-don-t-build-on-top-of-codox.md)

## Context

I initially thought reading metadata from source files is built into Grimoire but it is not
and has been implemented separately in projects like [lein-grim](https://github.com/clojure-grimoire/lein-grim). The implementation in `lein-grim` did not work with `.cljs` or `.cljc` files and so copying that
was not an option.

In a previous ADR I decided not to build on top of codox to generate documentation. I still
believe Codox is not what I want to generate final artifacts (HTML, JS Apps) but has
relatively solid features when it comes to reading source files and extracting metadata.

Codox' `:writer` option allows us to easily retrieve the raw data in a plain format that
is easy to understand.


## Decision

We will use Codox to retrieve metadata from source files for now.
Storage of metadata will be stored in Grimoire as before.

## Consequences

Codox does not provide means to read the source of vars. This will need to be implemented
separately, perhaps using `clojure.repl/source-fn` which however has it's own issues.

# 2. Don't build on top of Codox

Date: 2018-01-11

## Status

Superceded by [4. Utilize codox to read Clojure/Script sources](0004-utilize-codox-to-read-clojure-script-sources.md)

## Context

We want to derive data about Clojure projects that can be used to render API documentation
as well as plain text documentation (e.g. tutorials).

Codox is a popular tool to create this kind of documentation as HTML files.

## Decision

Since Codox renders to HTML instead of some more well defined data format it will be hard to
turn Codox' output into other formats. Due to this problem building on top of Codox is not
a viable path forward.

## Consequences

Some other tool will be needed to create data about APIs and other documentation.

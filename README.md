# cljdoc

An effort to create a central documentation hub for the Clojure & ClojureScript ecosystem.

- An update (read this first): [Hello everyone :wave:](https://github.com/martinklepsch/cljdoc/blob/master/doc/updates/0001-hello-everyone.md)

## Rationale

TODO (:sweat_smile:)

## Design

- an ecosystem-encompassing Grimoire store serves as single source of truth
- that Grimoire store is updated when new releases are published to Clojars or other relevant information changes
- various tools can be built on top of data in this Grimoire store
  - it acts as a repository for static analysis done on Clojure code

#### ADRs

I've been looking for an opportunity to use [ADRs](http://thinkrelevance.com/blog/2011/11/15/documenting-architecture-decisions) for some time, you can find them all in [`doc/adr/`](https://github.com/martinklepsch/cljdoc/tree/master/doc/adr).

## Quickstart for potential contributors

- `build.boot` contains various entry points into the code, most interesting is probably `build-docs` which you can run with

      boot build-docs --project bidi --version 2.1.3 target
  - After that there will be a bunch of stuff in `target/` which you may want to look at.
  - There are various sub-tasks that address different aspects of building documentation.  I hope reading `build-docs` will give you a quick overview about all the things going on.
- There are various specs defined in `cljdoc.spec`.
- Code that needs to be loaded for analysis is in `cljdoc.analysis`.
- The cache that serves as input to renderers is defined in `cljdoc.cache`
- Overall resource/routing model is defined in `cljdoc.routes`.

I've often chosen to refer to functions with their fully qualified name. I hope this makes reading the code easier.

I outlined some areas that could use help in [`CONTRIBUTING.md`](https://github.com/martinklepsch/cljdoc/blob/master/CONTRIBUTING.md)
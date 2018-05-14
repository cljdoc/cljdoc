# cljdoc

[![CircleCI](https://circleci.com/gh/martinklepsch/cljdoc.svg?style=svg)](https://circleci.com/gh/martinklepsch/cljdoc)

An effort to create a central documentation hub for the Clojure & ClojureScript ecosystem.

- An update (read this first): [Hello everyone :wave:](https://github.com/martinklepsch/cljdoc/blob/master/doc/updates/0001-hello-everyone.md)
- The old README can be found under [`doc/OLD_NOTES_MARTIN.md`](doc/OLD_NOTES_MARTIN.md)

> :wave: Need help getting started? Say hi on [Telegram](https://telegram.me/martinklepsch), [Twitter](https://twitter.com/martinklepsch) or [Clojurians Slack](http://clojurians.net/) (@martinklepsch).

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

I often choose to refer to functions with their fully qualified name. I hope this makes reading the code easier.

I outlined some areas that could use help in [`CONTRIBUTING.md`](https://github.com/martinklepsch/cljdoc/blob/master/CONTRIBUTING.md)

### Configuration

We use [`aero`](https://github.com/juxt/aero) for configuration and several modes of operation are possible:

- You should always, **without any further steps** be able to clone this
  repo and generate documentation locally using:

  ```clojure
  boot build-docs --project bidi --version 2.1.3 target
  ```
  If this does not work, it's a bug.
- The [cljdoc API server](/doc/server.md) requires some additional
  configuration as it will upload files to an S3 bucket and run analysis
  of jars on CircleCI instead of on the local machine.
  The additional configration can be placed into
  `resources/secrets.edn` or supplied via the environment variables
  specified in that file. Once these steps are completed you should be
  able to run the server using

  ```clojure
  CLJDOC_PROFILE=live boot start-api
  ```


## License

`EPL-2.0` see `LICENSE`

<img src="resources/public/cljdoc-logo-beta-square.png" width=100 height=100>

An effort to create a central documentation hub for the Clojure & ClojureScript ecosystem.

[For Library Authors](doc/userguide/for-library-authors.adoc) | [Contributing](CONTRIBUTING.adoc) | [Website](https://cljdoc.org/) | [ClojuTRE Talk](https://www.youtube.com/watch?v=mWrvd6SE7Vg)

> :wave: Need help getting started? Say hi on [Telegram](https://telegram.me/martinklepsch), [Twitter](https://twitter.com/martinklepsch) or [Clojurians Slack](http://clojurians.net/) in [#cljdoc](https://clojurians.slack.com/messages/C8V0BQ0M6/).

[![CircleCI](https://circleci.com/gh/cljdoc/cljdoc.svg?style=svg)](https://circleci.com/gh/cljdoc/cljdoc)

## Rationale

> :video_camera: I (Martin) gave [a talk at ClojuTRE](https://www.youtube.com/watch?v=mWrvd6SE7Vg) about cljdoc which is probably a good intro if you want to understand what cljdoc is and why it exists. If you prefer text, read on for the Rationale.

Publishing Clojure library documentation is an often manual and error
prone process. Library authors who want to provide documentation need
to set up tooling to create such documentation, host it and keep it
updated. In combination all these steps introduce a significant amount
of friction that often leads to there not being any HTML documentation
at all. If there is documentation it's often only a matter of time until
it's out of date with the latest release.

**In short:** Publishing documentation is hard. Harder than it has to be.

By fully automating the process of publishing documentation we can take
a great burden from the shoulders of library maintainers and let them focus
on shipping great libraries with great documentation.

A central place and consistent UI for all Clojure/Script library
documentation will also make it easier for developers to find and work
with documentation.

By centralizing this publishing process we can also build up a global
understanding of the Clojure/Script ecosystem enabling many more
interesting use-cases down the road.

#### Goals

- Provide an easy way to host library documentation for Clojure/Script library authors
- Deal with all the boring stuff: hosting, updating, keeping old versions around
- Build an ecosystem-encompassing database (+ API) of artifacts, namespaces and their contents.
- Support API documentation, articles and examples.
- Encourage the writing of more and better documentation.

## Contributing

1. Take look at our [Contributing file](CONTRIBUTING.adoc)
1. Get up and running by following the steps in [Running cljdoc locally](doc/running-cljdoc-locally.md)
1. Understand why things are the way they are by reading our [Architecture Decision Records](CONTRIBUTING.adoc#architecture-decision-records)

## License

`EPL-2.0` see `LICENSE`

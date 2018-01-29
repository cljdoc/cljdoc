# 8. Use CircleCI as Analysis Sandbox

Date: 2018-01-29

## Status

Accepted

## Context

Analyzing untrusted Clojure code means loading it which should only be done in some kind of
sandboxed environment. A Docker image has been created to help with this but this still
requires us to run and monitor job execution. Bad actors could still trigger many builds
to run Bitcoin miners and other compute-stealing stuff.

Alternatives to running Docker ourselves are AWS Lambda (probably similar compute-stealing
issues) and "hacking" a continous integration service to do the job for us. More detail can
be found in the [notes on Isolation](https://github.com/martinklepsch/cljdoc/blob/72da65055ab94942f33fb63b29b732e81b559508/doc/isolation.md)

## Decision

For a first version of cljdoc we will use CircleCI to run analysis for us. The result of
this analysis will be made available as a build artifact which can then be laoded in
a trusted environment to import data into Grimoire and build HTML (or other) documentation
frontends.

## Consequences

CircleCI's free tier comes with 1500 minutes of build-time. This will not be sufficient for
building documentation for the entire Clojure ecosystem. For the first few dozens of
projects we should probably be fine but at some point we'll reach the cap. At this point
we might want to ask CircleCI for sponsorship or look for alternatives.

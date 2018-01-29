# Isolation

Analyzing Clojure/Script code means loading it, loading it means it may do anything it wants. So we need a sandbox. This only affects a very small portion of the code, most of that can be found in `cljdoc.analysis`. There seem to be at least two options here:
- **Docker** is what I thought of first, but it would require us to run and maintain a server which is something I don't really want to do.
- **AWS Lambda** was mentioned by Daniel Compton. We may fit into their free tier which would make it, well, free. :slightly_smiling_face:

With all of these options there will still be issues that need consideration
- People could use our sandbox to "steal compute", e.g. mining Bitcoin etc.
- Any more issues to think about?

### Docker

- A setup and instructions can be found in `docker/`
- Running stuff in Docker means we will still need to have a machine
  on which we run Docker.

### CircleCI

- We can run the analysis on CircleCI
- The resulting data is stored as a build artifact
- Since we have the keys we can then load this data in an environment without special isolation and do all the remaining work
- This may work especially well in the beginning when we only operate on a few selected projects.

A setup for this can be found in the following places

- [`/scripts/analysis.sh`](/script/analyze.sh) - runs analysis on provided project, version, jar
- [`cljdoc-builder`](https://github.com/martinklepsch/cljdoc-builder) - is a github repo which defines build steps using the `analyze.sh` script
- [`/scripts/trigger-build.sh`](/script/trigger-build.sh) - triggers a build on CircleCI using the CircleCI API

### AWS Lambda

- To be evaluated...

### AWS Spot instances

- To be evaluated...


## Classpath Isolation

Besides this kind of process isolation there also is the problem of **Classpath isolation**. Right now we cannot build API docs for Codox or any of it's dependencies in different versions than what is already on the classpath. This could be solved by either anonymizing our or the dependencies' namespaces using something like [mranderson](https://github.com/benedekfazekas/mranderson). Both variants have their own tradeoffs. This is low priority but if it excites someone... :rocket:

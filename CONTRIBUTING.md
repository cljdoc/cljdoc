# Contributing

Hello there :wave: — nice to have you here.

There are several broad areas to this project where people may contribute. If you are interested in working on these please open an issue and state your intention to work on something.

I hope the README provides enough starting points to get going with the code. If it does not, please open an issue.

[Sandboxing](#working-out-sandboxing) | [GitHub Bot](#github-bot) | [Renderers](#writing-and-improving-renderers) | [Testing](#testing)

## Working out sandboxing

Analyzing Clojure/Script code means loading it, loading it means it may do anything it wants. So we need a sandbox. This only affects a very small portion of the code, most of that can be found in `cljdoc.analysis`. There seem to be at least two options here:
- **Docker** is what I thought of first, but it would require us to run and maintain a server which is something I don't really want to do.
- **AWS Lambda** was mentioned by Daniel Compton. We may fit into their free tier which would make it, well, free. :slightly_smiling_face:

Besides this kind of process isolation there also is the problem of **Classpath isolation**. Right now we cannot build API docs for Codox or any of it's dependencies in different versions than what is already on the classpath. This could be solved by either anonymizing our or the dependencies' namespaces using something like [mranderson](https://github.com/benedekfazekas/mranderson). Both variants have their own tradeoffs. This is low priority but if it excites someone... :rocket:

## Github Bot

Unfortunately many people don't tag their releases on Github and so it will be hard to relate API documentation to non-API documentation like a README or other stuff in `doc/`. A bot could help.

Some unfiltered ideas for what such bot could do (some may be triggered by new releases appearing on Clojars):

- Ask people to tag a release that exists on Clojars but not in their repo.
- Notify people about the existence of API docs for their library
  - This could be spammy but in my personal experience just being aware that you have an easy-to-use place to put documentation is a huge motivator to improve documentation
  - If they don't know about the documentation they won't link to it which decreases overall utility.
- Watch repositories for changes so we can rebuild non-API docs
  - I think people may need to add an "App" to their repo for this, not sure. If you know more, open an issue. :raised_hands:


## Writing and improving renderers
Renderers are what turns data from Grimoire into other representations. Most commonly renderers will be used to create representations of an artifact (e.g. `[bidi "2.1.3"]`). Because working with Grimoire directly can be a bit tricky a "cache" format has been designed. The idea behind caches is that they contain all information for one artifact bundled in one data structure. You can read more about this in [ADR-0005](doc/adr/0005-implement-minimal-api-abstracting-grimoire.md) and the `cljdoc.cache` namespace. Renderers should usually operate on a cache data structure. An example of this can be found in `cljdoc.renderers.html`.

#### Some renderers that I think would be useful
- **Static HTML pages** — Codox style HTML pages. Partly implemented but also lots of room for improvement. :sparkles: I think getting this right first could be a good exercise before starting work on other renderers. :sparkles:
- **Transit and/or JSON** — This would allow people to get ahold of the cache data structure for whatever purpose they want.
- **Single file HTML/JS** — Just hitting "save as" on this kind of page could be useful for long train rides :-)
- **Single page app** — with all the data we have I think there are many nice things to be done, ultimately that could culminate in a snappy app that allows you to navigate between versions, toggle between platforms etc.
- **Docsets** for documentation browsers like [Dash](https://kapeli.com/dash). Go, Elixir, Scala all have a canonical source that Dash integrates with directly, maybe this could also be a way forward.

With all of these I believe **link stability** is incredibly important and thus every route for every renderer is defined in one global thing in `cljdoc.routes`.

:warning: I will try to create a playground kind of thing where less Clojure savvy designers could work on the existing `cljdoc.renderers.html` code. Let's talk before you put any effort in working on existing or new renderers.

## Testing

The afore mentioned "cache" follows a spec and it seems very important that we maintain backwards compatibility while also being able to evolve. (The caches may be stored forever, even for old versions of the artifact.)

I'm not entirely sure what the best ways are to test all this but if you have thoughts I'm all ears.

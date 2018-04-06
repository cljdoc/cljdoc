# Land in Sight

Hey everyone! Since the last update a lot of work got done to make this available to the wider community.

That said I also resumed doing "actual work" and so I barely had any time over the last weeks. What follows below is an update on what has happened since the last update and a call for help 🙂

## Current State 

As before we can build documentation for any\* library from within this repo using:  

```  
boot build-docs --project bidi --version 2.1.3 target  
```

While this is great the real deal of this project is removing that step and making  the process of publishing documentation for Clojure libraries as easy as possible. So since the last update I implemented a documentation build-server that roughly works like this:
  
1. There's an API to request a documentation build for a specific version of a library on Clojars
2. Once called the API server will kick off a CircleCI job which then loads the (untrusted) library code and exports all relevant information as a build artefact
3. Via a webhook the API server is notified about completion of the CI job and then completes the process (render HTML, deploy to S3, etc.)
4. The deployed docs can then be viewed at: https://cljdoc.xyz/d/bidi/bidi/2.1.3/

Using CircleCI is a great little hack that allows us to ignore all the problems around running untrusted code for now.

The API call is a simple `curl` invocation with three params (project, version, jar URL). Details of that can be found in `script/cljdoc`. 

```
script/cljdoc build bidi 2.1.3    # => https://cljdoc.xyz/d/bidi/bidi/2.1.3/
                                  # => https://cljdoc.xyz/d/$group/$project/$version/
```

**I'm incredibly excited about this being fully automated and providing the community with a platform for improving documentation tooling on a ecosystem-reaching scale.**

Imagine we could deploy functionality to support dynadoc-style interactive examples, clojure.spec lookup, and more for the entire Clojure/Script ecosystem.

\* "any" is me being optimistic — if you find libs that don't work please open an issue.

## What's missing (a.k.a. plz help) 

As mentioned before I started doing "actual work" again and so it's even more frustrating that this has been lingering in a state where it's very close to useful but not quite there yet.

Things that are missing (see issues for details):

- Rewriting of Markdown links/image references [#12](https://github.com/martinklepsch/cljdoc/issues/12)
- SSL for API server (ideally automated in some way) [#13](https://github.com/martinklepsch/cljdoc/issues/13)
- Finalize/improve logging for users to view progress/failure of their documentation builds [#14](https://github.com/martinklepsch/cljdoc/issues/14)
- Improve namespace tree in sidebar [#15](https://github.com/martinklepsch/cljdoc/issues/15)
- Properly render :members of protocols [#16](https://github.com/martinklepsch/cljdoc/issues/16)
- Fix source extraction in analyser [#7](https://github.com/martinklepsch/cljdoc/issues/7) & [#17](https://github.com/martinklepsch/cljdoc/issues/17)
- Highlighting of code blocks from Markdown/Asciidoc [#18](https://github.com/martinklepsch/cljdoc/issues/18)

If you share my excitement about a community owned documentation platform for the Clojure/Script ecosystem please consider getting involved :)

If you'd like to be one of the very first libraries publishing their documentation to this new platform please reach out. I'd like to onboard a couple of people while in close contact to iron out remaining issues.

Thanks for reading!

--- 

Leave comments here or contact me privately on Slack. 

 




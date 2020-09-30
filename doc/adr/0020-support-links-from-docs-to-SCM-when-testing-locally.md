# 20. Support links from docs to SCM when testing locally

Date: 2020-09-08

## Status

Proposed

## Context

### Background

Cljdoc imports documentation from clojars artifacts and SCM (Source Code Management) repositories. 
The SCM is a git repository and usually one hosted on GitHub.

Documentation is imported to cljdoc from 2 distinct sources:

1. API docstrings from sources in a clojars artifact 
2. Stand-alone articles such as `README.md` and documentation under `doc/**` from an SCM repository.

It is not uncommon for documentation to contain links to other files under in the SCM repository. 
In these cases, cljdoc automatically rewrites these links to target the correct resource. 
If the SCM document is an article that has been imported to cljdoc, the link will target the imported article hosted by cljdoc,
otherwise the link will target the hosted SCM, which in most cases, is GitHub. 

### Issues with Testing Locally

Before a project is released to clojars and automatically imported to cljdoc, an author will often want to preview their
documentation by running cljdoc locally. 

Because cljdoc works from the published artifact, when testing locally, authors will first publish their project to their local maven repository.
This will remain true for whatever solution we choose here.

A current limitation of running cljdoc locally, as directed by current guidance, is that direct links to SCM files cannot be previewed. 
Concrete examples of non-functioning links are images, source code and articles.

These broken links make it difficult to get a real sense of how docs will appear and behave on cljdoc.

## Decision

After evaluating several alternatives (see below), I realize that our guidance on running cljdoc locally is more 
the issue than anything else.

I propose that we complement our guidance to include a workflow of importing from hosted SCM (i.e. GitHub) rather than just the local file system. 

The tester would commit their changes and push to a branch on their hosted SCM.
They would then ingest docs from this branch.
At doc browse time, because ingested docs are referencing a hosted SCM, the links to the SCM would automatically work.

The advantages are no extra local setup and an SCM preview that truly reflects production.

A minor con is that the author has to push to a hosted SCM.

## Alternatives

I came up with the following ideas when thinking about how to render links to SCM when ingesting docs from 
the local file system. I rejected them all when I realized that we could achieve a full preview with 
a change to documentation. 

I was focused on the case where the user has ingested their library from the file system (not hosted SCM).
The alternatives explore how we might rewrite broken SCM links to provide a better local preview.

**#1 [chosen] update docs**

See [Decision](#decision) above.

**#2 [rejected, not viable] rewrite SCM links to target the file system**

Targeting `file://` has the advantage of having nothing extra to run for the tester but is
simply not technically feasible; the browser prohibits it for security reasons.

**#3 [rejected] rewrite SCM links to target a local static web server**

The tester would launch a static web server from their project root.
A locally running cljdoc would render SCM links to the static web server.

While this would be easy to setup for the tester, the fetches would be to an unversioned
file system rather than SCM. Also the fetches would always be raw, if the browser/server
combo cannot figure out the MIME type, it would prompt for save/download. And links to source
would not support navigating to a specific line number.

Although this was my original plan, because it does not offer a full preview, 
I have since rejected it.

**#3 [rejected]  rewrite SCM links to target a local git instaweb server**

The tester would run `git instaweb` from his/her project root to startup a local SCM web server.

The advantages to this approach include easy setup for tester, fetches from SCM are versioned,
support for raw fetches and support for formatted fetches with ability to navigate to line number.

The cons are that git instaweb URLs and UI are not GitHub-like. 
We are also relying on the instaweb implementation remaining static, which seems to be a safe bet, but who knows?

Note that I have also experimented with [git instaweb via docker](https://hub.docker.com/r/leeread/gitweb-quick).

Although this was my secondary choice, I have since decided to that option #1 is simpler.

**#4 [rejected] rewrite SCM links to target a local GitHub URL compatible server**

There are GitHub clones like [gitea](https://gitea.io/en-us/) and [gogs](https://gogs.io/). 
Perhaps one of these could be setup to deliver a local project.
I had a brief look, but did not qucikly see something that would work, even via docker.
This does not mean this couldn't work, just that I gave up in favor of something simpler.

The pros are GitHub-like URLs and UI.

The cons are a more difficult setup by the user, more moving parts and the maintenance burden
of tracking an external component.

**#5 [rejected] rewrite SCM links to target a local custom GitHub compatible server**

I suppose this would not be terribly hard but it is also not something I want us to have to maintain.

**#6 [rejected] add preview support to cljdoc**

I'm not sure how this would work, but we could add preview support to the cljdoc production server.

This would be a larger project and have to address many more concerns, so I am rejecting for now.

## Consequences

Library authors will have guidance on how to get a fuller preview of what their documentation will look like on cljdoc before officially releasing.

As the change is documentation only (and any minor fixes discovered while testing docs), the main maintenance burden is limited to the docs.

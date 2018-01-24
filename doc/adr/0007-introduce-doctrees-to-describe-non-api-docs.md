# 7. Introduce DocTrees to describe Non-API Docs

Date: 2018-01-23

## Status

Accepted

## Context

Non API documentation such as articles and guides are just as
important—if not more important—as API documentation. **We want to
provide an easy way for projects to publish a collection of articles
in a hierarchy that makes sense for the project.**

Yada does an [exemplary job](https://juxt.pro/yada/manual/index.html)
at providing this kind of documentation and it's documentation may
serve as an example to keep in mind when considering approaches to tackle
this problem. [Re-frame's documentation](https://github.com/Day8/re-frame)
is also an interesting case as it is spread between it's README and other
files in the `doc/` directory of the project.

Generally I believe a good README on Github is desirable regardless of
more, likely more extensive, documentation elsewhere. Integration of
such README should be taken into consideration as well.

For a first version Markdown-only would be an acceptable limitation
but extending to support other markup languages such as Asciidoc
should be possible.

#### Grimoire

Since we use Grimoire as a storage system it would be nice to
integrate this non-API information into the Grimoire store as well.
A few challenges arise:

- Grimoire's support for articles and similar kinds of documentation is unfinished
- How would versioning work? This becomes especially tricky if we want
  to update non-API documentation as soon as it is changed in Git.
- How could a hierarchy of documents be encoded in Grimoire? Or should it even?


## Decision

We introduce a way for library authors to specify a hierarchy of
various documentation files they store in their Git repository. This
hierarchy can be encoded in familiar, hiccup-style vectors. A
simplified example, assuming some Markdown files in `doc/`:

```clojure
[["Getting Started" {:file "doc/getting-started.md"}]
 ["Guides" {}
  ["Integrating Authentication" {:file "doc/integrating-auth.md"}]
  ["Websockets" {:file "doc/websockets.md"}]]]
```

This information can then be used by cljdoc to create a manual or
book-like experience. This approach is partly inspired by [Sphinx' TOC trees](http://www.sphinx-doc.org/en/stable/markup/toctree.html).

Because Grimoire's support for articles is still in development
this ADR does not contain a decision on how to store the information
that cljdoc pulls out of projects using DocTrees.

#### More Details on DocTrees

A data structure may describe documentation hierarchy in the following way:

- ordered vector of vectors, somewhat hiccup inspired
- an attribute-map describes how the content of the given page can be
  retrieved from the Git repository
- elements after attribute-map are interpreted as children

This data structure may be specified by library authors through
a configuration file or derived from the repository structure.

```clojure
;; README only, perhaps derived
[["Readme" {:file "README.md"}]]
```

```clojure
;; Simple markdown files in doc/ — same as above
[["Getting Started" {:file "doc/getting-started.md"}]
 ["Guides" {}
  ["Integrating Authentication" {:file "doc/integrating-auth.md"}]
  ["Websockets" {:file "doc/websockets.md"}]]]
```

```clojure
;; Sections in a single asciidoc file
[["Getting Started" {:file "doc/content.adoc#getting-started"}]
 ["Guides" {}
  ["Integrating Authentication" {:file "doc/content.adoc#integrating-auth"}]
  ["Websockets" {:file "doc/content.adoc#websockets"}]]]
```

## Consequences

#### Monolithic Documentation

Projects like
[Fulcro](https://github.com/fulcrologic/fulcro/blob/develop/DevelopersGuide.adoc)
have one big file which contains all documentation. It would therefore
not be possible for Fulcro to describe a DocTree with items pointing
to individual files. Potential solutions include:
- splitting the file (change on side of Fulco)
- and parsing big files to derive a DocTree (change on side of cljdoc).

## Appendix

Below are some doc trees for projects with extensive documentation.

#### DocTree for [Yada manual](https://github.com/juxt/yada/blob/master/doc/yada-manual.adoc)

```clojure
[["Preface" {:file "doc/preface.adoc"}]
 ["Basics" {}
  ["Introduction" {:file "doc/intro.adoc"}]
  ["Getting Started" {:file "doc/getting-started.adoc"}]
  ["Hello World" {:file "doc/hello.adoc"}]
  ["Installation" {:file "doc/install.adoc"}]
  ["Resources" {:file "doc/install.adoc"}]
  ["Parameters" {:file "doc/parameters.adoc"}]
  ["Properties" {:file "doc/properties.adoc"}]
  ["Methods" {:file "doc/methods.adoc"}]
  ["Representations" {:file "doc/representations.adoc"}]
  ["Responses" {:file "doc/responses.adoc"}]
  ["Security" {:file "doc/security.adoc"}]
  ["Routing" {:file "doc/routing.adoc"}]
  ["Phonebook" {:file "doc/phonebook.adoc"}]
  ["Swagger" {:file "doc/swagger.adoc"}]]
 ["Advanced Topics" {}
  ["Async" {:file "doc/async.adoc"}]
  ["Search Engine" {:file "doc/searchengine.adoc"}]
  ["Server Sent Events" {:file "doc/sse.adoc"}]
  ["Server Sent Events" {:file "doc/sse.adoc"}]
  ["Chat Server" {:file "doc/chatserver.adoc"}]
  ["Handling Request Bodies" {:file "doc/requestbodies.adoc"}]
  ["Selfie Uploader" {:file "doc/selfieuploader.adoc"}]
  ["Handlers" {:file "doc/handlers.adoc"}]
  ["Request Context" {:file "doc/requestcontext.adoc"}]
  ["Interceptors" {:file "doc/interceptors.adoc"}]
  ["Subresources" {:file "doc/subresources.adoc"}]
  ["Fileserver" {:file "doc/fileserver.adoc"}]
  ["Testing" {:file "doc/testing.adoc"}]]
 ["Reference" {}
  ["Glossary" {:file doc/glossary.adoc}]
  ["Reference" {:file doc/reference.adoc}]
  ["Colophon" {:file doc/colophon.adoc}]]]
```

#### Notes on [Lacinia](https://github.com/walmartlabs/lacinia/tree/master/docs)

Published to [readthedocs.io](http://lacinia.readthedocs.io)

Writing out the DocTree isn't an issue here but the Lacinia docs use several
[ReStructuredText](https://en.wikipedia.org/wiki/ReStructuredText) features like
inlining contents of other files.

# 7. Introduce DocBundles for Non-API Docs

Date: 2018-01-21

## Status

Proposed

## Context

Non API documentation such as articles and guides are just as
important—if not more important—as API documentation. We want to
provide an easy way for projects to publish a collection of articles
in a hierarchy that makes sense for the project.

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

We introduce a *DocBundle* Thing into the Grimoire hierarchy that can contain
multiple documentation pages and describe their hierarchy. Nesting will be supported
in a limited fashion to keep things simple.

*DocBundles* are attached to the Version entity in the Grimoire store and their
contents are largely decoupled from other nodes in the store. 

*DocBundles* may contain the entirety of text required to render the documentation
or pointers to others sources required to render documentation (URIs etc.). An Example:

```clojure
[["Some Markdown" {:markdown "# Some markdown"}
  ["Some Remote Markdown" {:markdown-uri "https://markdown.com/some-file.md"}]]
 ["Some Asciidoc" {:asciidoc "= Some Asciidoc"}
  ["Some Remote Asciidoc" {:asciidoc-uri "https://some-asiicdoc.com/test.adoc"}]]]
```

This already encodes some hierarchy which is further discribed in [DocBundle Hierarchy](#docbundle-hierarchy)

- Some Markdown
  - Some Remote Markdown
- Some Asciidoc
  - Some Remote Asciidoc

> Generally copying information from the repo to some storage under our
> control seems preferable to not rely on services like Github. That
> decision however is not part of this ADR and the maps describing how
> to retrieve the content are intended to be an open system so that such
> decision may be made at a later point.

#### DocBundle Hierarchy

A data structure may describe their hierarchy in the following way:

- ordered vector of vectors, somewhat hiccup inspired
- an attribute-map describes how the content of the given page can be
  retrieved from the Git repository
- elements after attribute-map are interpreted as children

This data structure may be specified by library authors through 
a configuration file or derived from the repository structure.

> **Note:** The maps from the above section provide information where
> to find some document regardless of any context such as the repository.
> The datastructure defining the hierarchy, i.e. what you see below,
> should always refer to filepaths in the repository.

```clojure
;; README only, perhaps derived
[["Readme" {:file "README.md"}]]
```

```clojure
;; Simple markdown files in doc/
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

#### Versioning

Attaching *DocBundles* to a Version Thing ties documentation to a
version of an Artifact.  This is generally desirable but it is worth
noting that documentation may receive useful updates after a release
has been made.

An approach here may be that one Version Thing can have mulitple
*DocBundles*, each with a `:patch-level` key indicating how many commits
lay between the tagged release and the commit that served as foundation
for the generation of the *DocBundle*.

While this will probably not be very common this kind of approach will
fail if bigger refactorings and documentation updates are done in the
main branch of a repository.

#### Linking

Since documents in the *DocBundle* are decoupled from other nodes in the Grimoire store
there is no obvious way of allowing documentation authors to link to a namespace or function
at the same version of the viewed documentation.

An approach to this issue may be to allow users to use a specific
protocol like `grimoire://namespace/bidi.ring` and
`grimoire://def/bidi.ring/archive` but using these special protocol
will effectively make their source files less useful when viewed in a
standard Markdown renderer or similar.

Another option may be links that work under both situations and can be
specially treated, e.g. `cljdoc.com/bidi/bidi/CURRENT/bidi.ring/archive`. 
Opening that link in a browser could redirect to the latest version
while in our docs rendering we could replace `CURRENT` with the
current version.

This certainly needs more thought but the issue of linking in multiple contexts
is tricky and there probably won't be a perfect solution anyways.

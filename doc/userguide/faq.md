# FAQ

Short answers to common questions.

# How do I set SCM info for my project?

Source Control Management (SCM) info is information about where the source of your project is stored.

- **Leiningen**: Add a `:scm` key to your `defproject`, see [sample.project.clj](https://github.com/technomancy/leiningen/blob/master/sample.project.clj#L476).
- **Boot**: Pass the `:scm` option to the `pom` task, see [example](https://github.com/martinklepsch/derivatives/blob/f9cc6be8eeaf21513641cb09d5a466e34ecdd565/build.boot#L18-L23).

**Why do it?**

1. It's nice because it shows up on Clojars and people will find your project more easily.
2. cljdoc uses your Git repo to gather additional information, without the SCM info we can only show API documentation and cannot provide a backlink to your Git repo.

# Can we have badges?

Sure thing! cljdoc provides badges that will show the latest release
version as well as an endpoint that redirects to it.

- Badge URL: https://cljdoc.xyz/badge/re-frame
- Redirect URL: https://cljdoc.xyz/jump/release/re-frame

Using it in a Markdown file may look like this:

```markdown
[![](https://cljdoc.xyz/badge/re-frame)](https://cljdoc.xyz/jump/release/re-frame)
```

# How to update documentation?

Sometimes you make adjustments to documentation after cutting a release. 
In these situations it would be nice to update the docs on cljdoc as well.

Now when cljdoc reads documentation from your Git repository it does not
simply read it from `master` but instead tries to find a Git tag or a revision
in your project's `.pom`. This means you can build documentation for older releases
and generally decouples the output of the build from the time it was done.

This also means that to update documentation you **need to cut a new release** (for now).

I'd like to support some way to update docs after a release but it needs to be explicit
and hasn't been decided upon yet, see issue [#31](https://github.com/martinklepsch/cljdoc/issues/31)
for some additional discussion.

`SNAPSHOT` releases will use `master` as Git revision as they usually have no tag
in your repo or sha in a `.pom`. This can be useful for experimenting with cljdoc.

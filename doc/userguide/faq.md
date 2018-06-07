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

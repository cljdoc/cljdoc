# Articles

Besides API documentatino cljdoc allows you to publish guides and
articles to help users work with your library. Articles can be
provided through your projects Git repository using a configuration
file `doc/cljdoc.edn` (if this file is not present we will try to
infer the list of articles based on files in your Git repository).

To make articles available add a file `doc/cljdoc.edn` to your
repository containing something like this:

```clojure
{:cljdoc.doc/tree [["Getting Started" {:file "doc/getting-started.md"}]
                   ["Guides" {}
                    ["Integrating Authentication" {:file "doc/integrating-auth.md"}]
                    ["Websockets" {:file "doc/websockets.md"}]]]}
```

For a more real-world example, see the [`metosin/reitit`
docs](https://cljdoc.xyz/d/metosin/reitit/0.1.0/doc/introduction/) and
it's corresponding
[`doc/cljdoc.edn`](https://github.com/metosin/reitit/blob/master/doc/cljdoc.edn)

- For articles to work you need to specify a Git SCM in your pom.xml.
- Articles can be written in Markdown and Asciidoc.

#### Limitations

Relative links in the article source file may not always work.
We try our best to adjust them to their new location but if you find
broken links that should work, please open an issue.

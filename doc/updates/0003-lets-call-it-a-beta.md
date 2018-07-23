# Let's call it a beta

It's been a while that I posted an update here. Instead I recently just shared new stuff in the [#cljdoc](https://clojurians.slack.com/messages/C8V0BQ0M6/) channel on [Slack](http://clojurians.net/) as it got released. But since not everyone is following along there and a lot of stuff has been shipped and improved over the last months I figured I might post a more long-form update here as well.

## New & Improved Features

- **Much better documentation for library authors and users.**  
  Until recently there were no guides for people to start using cljdoc. There were some pointers here and there but no one-stop guide to get going. Now we have guides for library [authors](https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc) and [users](https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-users.md) pointing out the most important stuff.

- **Offline documentation bundles.**   
  I've said it a million times and I'll say it again: I'm a huge fan of tools like [Dash](https://kapeli.com/dash). They provide documentation on different language ecosystems in a streamlined user experience — and — they work offline. Cljdoc now also provides [offline-bundles to download](https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-users.md#offline-docs). I'm also actively working with he creator of Dash to integrate cljdoc as a third-party source.

- **Article "Inference".**   
  Until recently you had to add a `doc/cljdoc.edn` file to your project if you wanted to incorporate any Markdown/Asciidoc files beyond your Readme. While this configuration is still handy in more advanced cases you can now also just dump those files in your `doc/` directory and they'll be incorporated into your documentation. They'll be sorted alphanumerically so prefix the with `01-` to achieve the order you want. Or just provide a `doc/cljdoc.edn` and benefit from additional features like nesting. See the [docs on Articles](https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc#articles).

- **Much improved multi-platform support.**   
  If any aspect of a var or namespace differs across it's supported platforms those differences will now be highlighted. This applies to arglists, docstrings, source URLs etc. See [re-frame.interop](https://cljdoc.xyz/d/re-frame/re-frame/0.10.5/api/re-frame.interop) for a namespace where this applies.

- **Faster badges.**   
  If anyone has noticed their badges loading slowly, that should be fixed now by proxying to a new service called [badgen](https://badgen.now.sh/) (previously shields.io was used).

- **A service to access Clojars stats.**   
  While not a next-week type thing I'd like to incorporate download stats and other information into cljdoc. A step in that direction has been made by creating a [public service](https://github.com/cljdoc/clojars-stats) to run specific queries over Clojars download stats. Contributing is as easy as writing SQL! Also there's probably a lot of optimisations to make things faster. 

- **A new, much more beautiful and useful website and logo.**     
  [The website](https://cljdoc.xyz) now points to all kinds of relevant pieces of documentation and highlights core features of cljdoc. 
  <a href="https://cljdoc.xyz"><img  width="176" height="115" src="/resources/public/cljdoc-logo-beta.svg"></a>

## A Roadmap

Obviously it's a roadmap without dates but there's still some interesting stuff that might be coming up. Spec integration, user-contributed examples, a new storage layer etc.

Check out [the roadmap on GitHub](https://github.com/cljdoc/cljdoc/blob/master/doc/roadmap.adoc) and consider contributing if anything sounds worthwhile and/or interesting to you :slightly_smiling_face:

## Give it a shot! 

If you maintain or contribute to a library consider taking a closer look at cljdoc. It's already pretty awesome and it'll only get better (no bias here :slightly_smiling_face:)

— [Martin](https://twitter.com/martinklepsch/), always happy to hear from you :slightly_smiling_face:
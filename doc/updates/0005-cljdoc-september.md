Hey dear ClojuristsTogether crew! This is [Martin](https://twitter.com/martinklepsch) with a quick update on cljdoc.

September has been a somewhat slow month. I've been focused on
preparing my ClojuTRE talk about cljdoc the first half of September
and was mostly offline — on a sailboat — the second half of
September. You can watch [the talk on
YouTube](https://www.youtube.com/watch?v=mWrvd6SE7Vg). I'm also
thanking all of you at the end of the talk.

Despite me being absent
[some](https://github.com/cljdoc/cljdoc/pull/116)
[PRs](https://github.com/cljdoc/cljdoc/pull/117) were opened &
merged. Thanks to [Travis McNeill](https://tavistock.github.io) and
[Saskia Lindner](http://www.saskialindner.com) for that. It's great to
see people contributing and I hope to grow the cljdoc community
further over the next months.

In the same vein Saskia and I are putting together a cljdoc hackday in
Berlin on Thursday 11th October from 2pm, join the `#cljdoc` Slack
channel on [clojurians.net](http://clojurians.net) for details.

Things that have have been shipped in September:

- A toggle to view raw docstrings ([PR #117](https://github.com/cljdoc/cljdoc/pull/117))
- A first iteration at what may become an interactive article TOC, currently just showing what section you're in ([PR #116](https://github.com/cljdoc/cljdoc/pull/116))
- OpenGraph meta tags (cljdoc links should render much nicer on Slack, Twitter & co)
- An [issue](https://github.com/cljdoc/cljdoc/issues/113) with UTF-8 article slugs has been fixed
- Cleanups in various places of the code removing unused code
- Improvements to the way the classpath is constructed for analysis ([commit](https://github.com/cljdoc/cljdoc/commit/422f4636167d3534a9b636faf3d5c2ca7fa04eeb))
- A bug with links in offline docs has been fixed ([commit](https://github.com/cljdoc/cljdoc/commit/125f4f6c6ccd0e93e3c89bd44834e16248f2d55d))

After ClojuTRE and my vacation I'm feeling energized to work on cljdoc
again in October. Priorities will be the integration of examples and community building.

**If you want to help cljdoc with 5 minutes of your time:** [add a
badge](https://github.com/cljdoc/cljdoc/blob/master/doc/userguide/for-library-authors.adoc#basic-setup)
to your project's Readme. In order to achieve the vision I outlined in
my [ClojuTRE talk](https://www.youtube.com/watch?v=mWrvd6SE7Vg) the community needs to be aware this thing exists —
and that's not something I'll ever be able to achieve on my own.

So point people to cljdoc and — if you're feeling particularly excited — tweet
or write a blogpost about it.

Thanks for your support <3

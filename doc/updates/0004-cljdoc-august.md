> This update has been written as part of my ClojuristsTogether grant and has also been published on the [ClojuristsTogether blog](https://www.clojuriststogether.org/news/august-2018-monthly-update/) at the end of August 2018.

I switched the **storage layer**. SQLite is now used instead of lib-grimoire. More details in the respective architecture decision record: [ADR-0013](https://github.com/cljdoc/cljdoc/blob/master/doc/adr/0013-move-to-sqlite-for-storage.md)

This was a lot of work but it sets the project up for more interesting extensions besides API documentation and articles (think specs & examples).

Also I looked more into **integrating specs** but without changes to spec it is impossible to determine if a spec originates from the artefact that is being analysed or from one of it's dependencies. To fix this specs will need to support metadata ([CLJ-2194](https://dev.clojure.org/jira/browse/CLJ-2194)) but the timeline for this is unclear.

In the light of this I'm considering focusing on examples first.  More details to come. 

Some more minor things that happened:

- I printed [**stickers**](https://twitter.com/martinklepsch/status/1037802412680126464) which I'm planning to send to contributors. 
- Bozhidar likes favicons so I added one :) 
- Work is underway to integrate cljdoc into [Dash](https://kapeli.com/dash)
- Various fixes to the analyser code, mostly to eliminate slight differences between Clojure and ClojureScript as well as some dependency related improvements.
- I shipped a [**quick switcher (demo)**](https://giant.gfycat.com/GoodCluelessKusimanse.mp4) that allows you to switch between projects that you opened recently. I hope to expand this to quickly finding vars, namespaces and articles in the current project.

I'll also be at [ClojuTRE](https://clojutre.org/2018/) next week. Say hi if you're around! 👋

Oh and after ClojuTRE I'll be on a sailboat for two weeks so there will be less activity than usual. 

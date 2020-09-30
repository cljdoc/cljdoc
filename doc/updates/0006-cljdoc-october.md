Hey again dear ClojuristsTogether crew! Those last three months flew by and so much stuff has happened around cljdoc!

For me the most important things were onboarding more contributors and allowing library authors and users to add examples to their APIs. 

## The Good News

The influx of people to cljdoc has been really amazing, there were about 10 new contributors, some of them really stepping up by helping to review and merge pull requests as well as supporting other newcomers in [our Slack channel](https://clojurians.slack.com/messages/C8V0BQ0M6/).

[**27(!) pull requests**](https://github.com/cljdoc/cljdoc/pulse/monthly) by 10 authors were merged, 40 issues were active, with 28 of them now closed. Shout out to Avichal, Saskia, Daniel, Albrecht, Jorin, Greg, Martin, Travis, Randy and everyone else who contributed through discussions and feedback! 

I believe a wide contributor base is critical to ensure longterm success of cljdoc and I look forward to welcome more contributors in the future.


## The Bad News 

Examples... well. I didn't ship them. I spent a fair amount of time on it but eventually decided that it's not the right thing to focus on at this point. For examples to really make an impact cljdoc adoption needs to be much higher and there's a lot more stuff that I perceive as more impactful at this stage.

There also needs to be more discussion with the wider ecosystem to make examples useful and maintainable (~testable). Please hit me up if you have thoughts in that direction!

More details in [ADR-0014](https://github.com/cljdoc/cljdoc/blob/master/doc/adr/0014-add-support-for-examples.md) (initial decision to integrate examples) and [ADR-0015](https://github.com/cljdoc/cljdoc/blob/master/doc/adr/0015-cancel-work-on-examples.md) (reversal of that decision with more context/reasoning).

## Things that have have been shipped in October:

- We migrated from cljdoc.xyz to cljdoc.org and made lots of tiny improvements to make sure cljdoc is being indexed by search engines properly.
- Avichal added build stats to [cljdoc.org/builds](https://cljdoc.org/builds) giving us some insight into failure rates of documentation builds. [Help lower it.](https://github.com/cljdoc/cljdoc/blob/master/…)
- Randy improved our [404 page](https://cljdoc.org/clojurists-together-rules) by adding the familiar search that is available on the front page.
- We set up JS packaging making docs load even faster. cljdoc's Lighthouse performance score now is 99. 
- @rakyi helped set up Prettier so our JavaScript code is consistently formatted. This is something that we might also do for Clojure code in the future.
- @jsimpson-ovo built out proper support for GitLab. This mostly worked before but now it's is on par with GitHub. Source URLs, article edit links and more just work now.
- Lots of improvements aimed at new contributors. Better support for Cursive, an improved [`CONTRIBUTING`](https://github.com/cljdoc/cljdoc/blob/master/CONTRIBUTING.adoc) and better instructions for [running cljdoc locally](https://github.com/cljdoc/cljdoc/blob/master/doc/running-cljdoc-locally.adoc).

## What next?

With examples on hold and spec integration [still being semi-blocked](https://github.com/cljdoc/cljdoc/issues/67) there is some time to explore other areas. Some things I'm looking forward to in particular: 

- A ubiquitous search interface to find functions, articles and switch between recently viewed projects. ([#194](https://github.com/cljdoc/cljdoc/issues/194))
- Integration of download statistics from Clojars ([#68](https://github.com/cljdoc/cljdoc/issues/68))
- Showing a project's dependencies and license (also [#68](https://github.com/cljdoc/cljdoc/issues/68))
- Various search engine optimisations ([#192](https://github.com/cljdoc/cljdoc/issues/192), [#164](https://github.com/cljdoc/cljdoc/issues/164) & [#160](https://github.com/cljdoc/cljdoc/issues/160))


## Tell people about cljdoc

Quoting somebody who came by the [#cljdoc Slack channel](https://clojurians.slack.com/messages/C8V0BQ0M6/) recently (emphasis mine :P):

> Hey. Just wanted to say thanks to the authors. Was looking for a way to document my cljs library, tested a few other tools, none of them would document the (hundreds of) dynamically generated functions. Even thought about writing my own. Forgot about it until I stumbled on cljdoc and the docs are already built! And it works perfectly! **This project needs more promotion.**

So point people to cljdoc and — if you're feeling particularly excited — tweet or write a blogpost about it.

## Thanks

Thanks for your support! I'm excited to follow Nikita and Arne's work over the next months and feel truly grateful that an initiative like ClojuristsTogether exists in our community.

	

# 11. Use Tachyons As CSS Foundation

Date: 2018-05-03

## Status

Accepted

## Context

A primary component of cljdoc is a web application. As part of our
work on this web application we regularly need to implement new UI
elements or flows to support overall product development. This
frontend work requires usage of CSS to specify positioning, text
styles and many more variables.

A common problem with CSS is that developers try to generalize CSS
classes so that they can be reused (see
e.g. [BEM](http://getbem.com/)). Arguably the intention is great but
inevitably the time will come when constraints change and so the
component's CSS is modified. By that time other people may have used
that component in other places relying on the current implementation.

In programming languages breaking a collaborator's expectation like
this can be mitigated using assertions or automatic tests but this is
less easily done when working with CSS.


## Decision

In order to avoid the problems outlined above we will adopt the
approach of using atomic, immutable utility classes as promoted by the
[Tachyons](http://tachyons.io/) library.

Tachyons provides safe-to-reuse, single-purpose classes that help with
achieving consistent scales of whitespace and font-sizes.

By not modifying the definition of CSS classes anymore we can safely
build out UI components using those classes without needing to worry
if we're breaking someone else's expectations.

## Consequences

- Tachyons can be a bit weird when not being familiar with the general
  approach. While I believe it will enable contributors to move more
  confidently & quickly in the long run we might need to go the extra
  mile to make them buy into this approach.
- Previously reuse what at the level of CSS classes. With an approach
  like Tachyon's reuse will get elevated to the component level.

## Appendix

- [CSS and Scalability](http://mrmrs.github.io/writing/2016/03/24/scalable-css/) — 
  an insightful article on why utility classes are a good idea by the author of Tachyons. 
  A quote from that article:  

  > In [the monolith] model, you will never stop writing
  > css. Refactoring css is hard and time consuming. Deleting unused
  > css is hard and time consuming. And more often than not - it’s not
  > work people are excited to do. So what happens? People keep
  > writing more and more css
- [tachyons-tldr](https://tachyons-tldr.now.sh) — super helpful tool
  to look up classes provided by Tachyons via the CSS attributes they
  affect.
- [dwyl/learn-tachyons](https://github.com/dwyl/learn-tachyons) — a
  nice repository with another 30s pitch and various examples
  outlining basic usage.

# Don't Ship Examples Just Yet

## Status 

Accepted (supersedes [ADR #0014](0014-add-support-for-examples.md))

## Context 

I'm having second thoughts on shipping examples as one of the next main things for cljdoc. Second to a [clojure.spec integration](https://github.com/cljdoc/cljdoc/issues/67) it would be a huge feature but I'm no longer sure it's impact will be proportional.

Many people suggested examples as a feature for cljdoc but I think this might be more due to their familiarity with them from sites like clojuredocs.org — rather than library authors actively looking for ways to add structured examples to their libraries.

Various hypothesis that would support the addition of examples haven't been tested or verified sufficiently:

- Are examples useful for libraries? Their usefulness is mostly proven with regards to standard library functions which are tiny in scope and mostly operate on plain data. Libraries often require more complex setup.
- Will library authors add examples to their libraries?
- Is a community-repository necessary? This has been mostly introduced to allow adding examples to Clojure itself. 

## Decision 

Don't ship examples just yet.

cljdoc is great because library authors don't have to do anything to get great looking documentation, let's keep it that way and focus on broader adoption, i.e. features that deliver value without requiring extra work from library authors.

## Consequences

No examples. Also some freed up time that can be directed towards other features that scale proportional to users rather than already-busy library authors doing even more work.

A few days of work sunk into this and the progress is preserved in the branch `70-examples` for now.

## Appendix

- When this is re-approached at a later stage consider that examples should be testable in some way. It seems to me that this is the only way to ensure library authors will keep examples up to date.
- Maybe [REPL transcripts](https://github.com/cognitect-labs/transcriptor) could be an interesting approach here too. 
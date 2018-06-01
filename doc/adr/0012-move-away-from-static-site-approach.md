# 12. Move Away From Static Site Approach

Date: 2018-06-01

## Status

Accepted

## Context

When starting to build cljdoc I was somewhat reluctant to accept the idea of managing a service people would rely on in my spare time. This has lead to an architecture where all docs are built on an otherwise inaccessible server and then deployed to S3. This is great for uptime but the problem with that approach quickly became obvious: any time I made a tweak to how documentation is rendered I would have to rebuild all documentation.

This is a hard blocker given the current iteration speed.

## Decision

Move away from rendering documentation to files and serving them via S3 to rendering HTML as it is requested.

## Consequences

Uptime and performance were a key motivation for the decision to host docs statically. Rendering docs on a server may impact both of these and will probably require some work to ensure they are not affected too much.

Performance should be solvable by putting a CDN in front of the server but so far response times are reasonable.

A CDN may also improve uptime but this of course depends on it already being cached so there's no strict guarantees.

Overall moving towards rendering stuff dynamically will allow for much faster iteration speed and make it easier for contributors to get started locally.

## Appendix

This decision has been made around May 12th, see [this commit](https://github.com/martinklepsch/cljdoc/commit/361f8d95c1ed34d8467778c0adf6bb75859a6c5e).

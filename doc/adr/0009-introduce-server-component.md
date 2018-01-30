# 9. Introduce Server Component

Date: 2018-01-30

## Status

Accepted

## Context

While most of cljdoc's publishing pipeline could be ran with
"job-runners" (e.g. CircleCI, AWS Lambda) the isolation provided by
those isn't required for many parts of the process.

Furthermore some API to trigger documentation builds will be required
at some point and this API will need to hold some credentials, e.g.
AWS keys for S3 and CircleCI API tokens. Exposing access to job-runners
directly would make it harder to implement things like rate-limiting etc.

An API may also be useful to expose further utility endpoints, like
ways to list available versions or download the metadata for a given
namespace or artifact.

## Decision

We will implement a server component which has the following responsibilties:

- be a central API to end users to request documentation builds
- hold credentials for various services which should not be exposed in analysis sandbox
- manage flow of documentation builds
  - trigger analysis of jars
  - receive webhooks about completed analysis
  - populate Grimoire store using analysis results and a project's Git repository
  - build HTML documentation based on data in Grimoire store
  - deploy HTML documentation to S3/Cloudfront

## Consequences

A running process introduces some operational and monitoring complexity. Specifically
this means we'll need to think about:

- How to monitor for failure and diagnose failure?
- Who to notify in case of failure?
- Who pays for the server?
- Who has access to the serer?

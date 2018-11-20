# Prefer Polling over Webhooks

## Status

Accepted (related to [ADR #0008](0008-use-circleci-as-analysis-sandbox.md))

## Context

cljdoc uses CircleCI as a sandbox to run analysis on projects as
outlined in [ADR #0008](0008-use-circleci-as-analysis-sandbox.md).

The process currenlty works like this:

1. cljdoc queues a build
1. CircleCI eventually runs the build
1. cljdoc is notified via a webhook

This approach is nice because we don't need to constantly check the status
of a build until it eventually finishes but it also has a few drawbacks:

- Testing anything involving webhooks in a local development
  environment is a serious pain
- Code related to running the analysis and storing it's result are
  spread accross multiple places making the code harder to follow and
  maintain

## Decision

Move to a model where the respective analysis service (CircleCI or Local)
exposes a blocking interface for running the analysis.

For CircleCI this blocking interface will simply poll the CircleCI build until
it reached [lifecycle `"finished"`](https://circleci.com/docs/api/v1-reference/#build).

## Consequences

Unsolved problems applying to both approaches:

**Restarting the cljdoc server can cause a loss of state.**

- Webhooks may arrive at a time the server is restarted/offline.
- Background processes running analysis may be terminated by
  restarting the service.

**It may take longer until analysis is finished.**

- We poll whether the analysis job completed every 5 seconds.
- So in consequence, loading the result of analysis may be delayed by
  up to 5 seconds from the moment the job finished.

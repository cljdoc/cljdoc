# Use Nomad For Deployment

## Status

Accepted

## Context

cljdoc's deployment story has been simplistic but effective. To recap:

- During CI a zip file is pushed to S3 that contains all files to run the application
- On the live server there is systemd service that will download an archive and run it. The
  version of the downloaded archive is specified via a file on the server.

Updating simply required updating a file on the server and restarting the service.

The issue with this approach however is that every time a new release was pushed to the server
the restart of the systemd service would incur up to a minute of downtime. While this generally
isn't a huge deal it discourages certain development practices that may be desirable such as
Continuous Deployment.

Our existing deployment setup (and tools used by it) are poorly equipped to handle this kind of
deployment scenario. A large amount of bash scripts would be required to start a new cljdoc server,
wait for it to become available, update nginx's upstream port, and kill old cljdoc server instances
in a repeatable, automated manner. Likely these bash scripts would be error-prone and turn into
something nobody likes to touch.

## Decision

Implement a *canary deploy* mechanism for the cljdoc server application using
[Nomad](https://nomadproject.io) and [Traefik](https://traefik.io).

While both of these tools are probably aimed at much more complex workloads they provide the
following benefits over the existing systemd/Nginx setup:

- Automatic SSL certificates via Lets Encrypt
- Declarative specification of jobs and their desired update semantics
- APIs to schedule new jobs and cycle old/new deployments
- Health checks to verify new deployments work as expected
- Machine images become much simpler since they only need Nomad, Consul, Docker

This simplifies a lot of cljdoc's operational tasks while also enabling Continuous Deployment.

## Consequences

#### Inefficient Resource Allocation

The way Nomad [handles canary deployments](https://www.nomadproject.io/guides/operating-a-job/update-strategies/blue-green-and-canary-deployments.html) requires that there are sufficient resources available to run two sets of tasks side by side.

This results in instances only operating at half their capacity. In practice the cljdoc
server never ran into resource constraints so this likely won't cause any actual problems
but it's an imperfection nonetheless.

> **Note:** The scaling plan for cljdoc has always been to put a CDN in front of the backend.

#### More Complexity

Nomad and Consul are complex tools, designed for multi-instance orchestration. Compared to
shell scripts this also forces additional complexity upon developers trying to work with these
tools on their own machines.

#### Atypical Nomad Usage

Running a single node "cluster" is also an atypical usage scenario and thus may receive limited
support or improvements in the future.

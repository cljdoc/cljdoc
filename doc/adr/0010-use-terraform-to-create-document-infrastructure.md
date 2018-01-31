# 10. Use Terraform to Create & Document Infrastructure

Date: 2018-01-31

## Status

Accepted

## Context

Running a service like cljdoc requires infrastructure of various kinds. For
the initial prototype I generated a static site stack using [a library I
created](https://github.com/confetti-clj/confetti) which uses AWS
CloudFormation under the hood. This has been useful while prototyping but
with the addition of [a server component](/doc/adr/0009-introduce-server-component.md)
and potentially other infrastructure components in the future it becomes
harder to stick to Cloudformation.

[Terraform](https://www.terraform.io/) seems to be a popular tool in this space
and provides all the functionality of my library as well as support for using
multiple cloud providers.

## Decision

Rebuild the static site stack using Terraform. Once that has been transitioned,
extend the Terraform configuration to include a server that we can use to host
the server component of cljdoc.

## Consequences

Terraform is an additional tool compared to just using Boot. Since this is only
needed by admins I'm not to worried about this.
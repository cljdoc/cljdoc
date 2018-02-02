# cljdoc Server

As per [ADR-0009](/doc/adr/0009-introduce-server-component.md) we introduced a server side component to cljdoc.

This document is intended to give an overview around the various libraries and namespaces used for this server.

## What does the server do?

- Accept requests to build documentation for a provided library & version 
- Queue CircleCI jobs to run analysis of a libraries code
- Accept notifications (webhooks) from CircleCI about completed jobs
- Download and use build artifacts from CircleCI as well as a
  library's repository to generate documentation pages and deploy them
  to S3
- The server **does not serve any documentation**. This is handled by
  S3 and Cloudfront which can guarantee far better uptime. :wink:
  
### Running the server

```sh
# Note that this requires various secrets (see secrets.edn)
CLJDOC_PROFILE=live boot start-api
```

### Future plans

- Provide means for users to inspect the progress/logs of their build
- Provide an API for Grimoire queries which are not easily servable via S3
  (e.g. indexes, search generally, ...)

## Libraries Used

- `yada/lean` is used for HTTP server and routing (Bidi)
- `aero` for configuration
- `integrant` for dependency injection
- `metosin/jsonista` is used for parsing and generating JSON

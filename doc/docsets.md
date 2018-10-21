# cljdoc docsets

Downloading documentation for offline use can be very useful when
travelling or just swinging in your hammock, slightly to far away from
wifi. This page tries to summarize the important API endpoints to use
docsets. It was originally written as a guide for the creator of
[Dash](https://kapeli.com/dash) to integrate cljdoc as a third party
source.

All code is assuming a `bash` process with the following vars:

```sh
group_id="bidi"
artifact_id="bidi"
version="2.1.3"
```

It is also assumed that the [Clojars search API](https://github.com/clojars/clojars-web/wiki/Data#json-search-results) is used to find existing project/version combinations.

### Check if an offline bundle is available

```sh
curl -OJ https://cljdoc.org/download/$group_id/$artifact_id/$version
# If we have docs available:
# - a zip-file will be sent as response with a 200 status code
# - else 404
```

### Trigger a build for a project

If the previous request returned a `404` you may want to trigger a build for
the respective project. You can do so by sending the following request:

```sh
curl -X POST -d project=$group_id/$artifact_id -d version=$version https://cljdoc.org/api/request-build2
```

### Monitoring build progress

The previous will redirect you to a URL like `/builds/123` if everything worked correctly.
Request this page with an appropriate `Accept` header to receive build information in JSON or EDN:

```sh
curl -s -H 'Accept: application/json' https://cljdoc.org/builds/123
```

The following two fields are useful to determine if a build is done/succeeded:

- `import_completed_ts`: if non-null the build succeeded and docs are now available
- `error`: if non-null there was an issue preventing docs from being built

> **Note:** Before 2018-06-12 the `error` field was set to indicate
> minor issues as well so older builds may have this field set while
> at least API docs were built successfully.

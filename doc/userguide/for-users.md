# cljdoc for Library Users

## Finding Documentation

To find documentation use the search field on the [cljdoc homepage](https://cljdoc.org)
or navigate to documentation directly by entering the appropriate URL.

URLs are formed like this:

```sh
# https://cljdoc.org/d/$group_id/$artifact_id/$version
https://cljdoc.org/d/ring/ring-core/1.6.0
```

If we haven't built documentation for the requested project yet you'll
see a page with a button to trigger a build. Builds usually don't take
longer than 1-2 minutes.

## Offline Docs

cljdoc allows users to download bundles of documentation for offline use. To do so just navigate to

```sh
# https://cljdoc.org/download/$group_id/$artifact_id/$version
https://cljdoc.org/download/reagent/reagent/0.8.1
```

or download the bundle using curl (note the `-OJ` flags):

```sh
curl -OJ https://cljdoc.org/download/reagent/reagent/0.8.1
```

Also see the [Docsets](/doc/docsets.md) page for more information.

Integrated support for offline documentation browsers - [Dash for MacOS](https://kapeli.com/dash) 
can already download cljdoc bundles and
support for other tools such as [Zeal](https://zealdocs.org/) is underway.

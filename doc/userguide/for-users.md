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

Cljdoc supports the downloading of a library's docs (aka docset) for offline use.
The cljdoc download docset format is a zip file.

[Dash for MacOS](https://kapeli.com/dash) supports and downloads zip docsets directly from cljdoc.org.

If you are a [Zeal](https://zealdocs.org/) user, you will be delighted by [@Ramblurr](https://github.com/Ramblurr)'s [cljdocset](https://github.com/Ramblurr/cljdocset) tool.
It downloads cljdoc zip docsets and converts them to Dash docsets that can easily be added to Zeal.

If you want to, for whatever reason, download zip docset files for your own use, navigate to:

```sh
# https://cljdoc.org/download/$group_id/$artifact_id/$version
https://cljdoc.org/download/reagent/reagent/0.8.1
```

or download an offline docset zip file using curl (note the `-OJ` flags):

```sh
curl -OJ https://cljdoc.org/download/reagent/reagent/0.8.1
```

See the [Docsets](/doc/docsets.md) page for more technical information.
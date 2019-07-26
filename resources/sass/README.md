# Asciidoctor Sass Support

## Overview
The Asciidoctor team builds its default stylesheet from the [asciidoctor-stylesheet-factory project](https://github.com/asciidoctor/asciidoctor-stylesheet-factory).
We use this project as a base and have it bring in its dependencies on:
- zurb foundation - the base styling for asciidoctor
- compass - stylings and sass compiler
We bring in [tachyons-sass](https://github.com/tachyons-css/tachyons-sass) for our convenience to help match the styling strategy used in cljdoc.

## Prerequisites
Ruby - because asciidoctor-stylesheet-factory needs Ruby so do we.

## Sass
Our sass is in 2 files:
1. [cljdoc-asciidoc.scss](cljdoc-asciidoc.scss) - contains styling overrides
2. [settings/_cljdoc-asciidoc.scss](settings/_cljdoc-asciidoc.scss) - contains styling variable overrides

## Building
Run `install-deps.sh` to bring down dependencies.
Run `build.sh` to build cljdoc asciidoctor stylesheet `cljdoc-asciidoc.css`.

## Deploying
For now, manually copy the generated `target/cljdoc-asciidoc.css` to cljdoc `resources/public` and commit your changes to cljdoc when satisfied.

## Not Replicated
We do not currently replicate the following asciidoctor-stylesheet-factory features:
1. The build script for asciidoc-stylesheet-factory does some minor fixups on the generated css via sed and grep.
2. It also employs cssshrink to reduce the size of the generated css. Should we wish to minify web assets, I think we can do this at the cljdoc build level.

## Why SASS?
It offers a few advantages for us:
1. it is what asciidoctor uses to build their stylesheets which makes it easy for us to take advantage of, and stay in synch with, their hard work.
2. we can easily scope all asciidoctor styling under the `asciidoctor` css style.
3. it allows us to bring in tachyons styles and reference them by meaningful names.

## Why Not Dart Sass?
We want to mimic what asciidoctor-stylesheet-factory does as much as we can.
Dart Sass is the current recommended solution for Sass, but it has taken a strategy of strictness, and won't even compile asciidoctor Sass.

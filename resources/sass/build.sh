#!/usr/bin/env bash

set -eou pipefail

if [ ! -d .sass-deps ]; then
    echo "Dependencies not found, have you run install-deps.sh yet?"
    exit 2
fi

echo "--[compiling sass to css]---"
ADOC_DIR=./.sass-deps/asciidoctor-stylesheet-factory
BUNDLE_GEMFILE=${ADOC_DIR}/GemFile bundle exec compass compile \
       --require 'zurb-foundation' \
       --require "${ADOC_DIR}/lib/functions" \
       --sass-dir '.\' \
       --css-dir 'target' \
       --no-line-comments \
       --output-style compact \
       --import-path .sass-deps \
       --import-path .sass-deps/asciidoctor-stylesheet-factory/sass
echo "--[done]---"

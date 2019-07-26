#!/usr/bin/env bash

set -eou pipefail

if [ hash bundle 2> /dev/null ]; then
    echo "Error: Ruby's bundle command not found. Have you installed ruby?"
    exit 2
fi

echo "--[cloning non-ruby deps]---"
rm -rf .sass-deps
mkdir .sass-deps
(cd .sass-deps
 # we stick to specific versions for repeatability
 git clone --branch v4.9.5 --depth 1 https://github.com/tachyons-css/tachyons-sass.git
 git clone https://github.com/asciidoctor/asciidoctor-stylesheet-factory.git
 cd asciidoctor-stylesheet-factory
 git reset 6a4ef9fcfba1b17a3ceba05c896cb8ac14cb87ba --hard)

echo "--[installing ruby gems]---"
ADOC_DIR=./.sass-deps/asciidoctor-stylesheet-factory
BUNDLE_GEMFILE=${ADOC_DIR}/GemFile bundle install

echo "--[done]---"

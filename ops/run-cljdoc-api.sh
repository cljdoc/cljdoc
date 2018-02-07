#!/usr/bin/env bash

set -euo pipefail

# TODO print instructions if file does not exist
version=$(cat "$HOME/CLJDOC_VERSION")

if [ ! -d "cljdoc-$version" ]; then
    echo "Downloading cljdoc v$version"
    # TODO fail if 404
    curl -Ls "https://github.com/martinklepsch/cljdoc/archive/$version.tar.gz" -o "cljdoc-$version.tar.gz"

    echo "Unpacking archive"
    tar -xvf "cljdoc-$version.tar.gz"
    rm "cljdoc-$version.tar.gz"
fi

pushd "cljdoc-$version"

echo "Starting process"
CLJDOC_PROFILE=prod boot start-api

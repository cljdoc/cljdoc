#!/usr/bin/env bash

set -euo pipefail

# TODO print instructions if file does not exist
version=$1 #(cat "$HOME/CLJDOC_VERSION")
archive="cljdoc-$version.zip"

if [ ! -d "cljdoc-$version" ]; then
    echo "Downloading cljdoc v$version"
    # TODO fail if 404
    curl -Ls "https://s3.amazonaws.com/cljdoc-releases-hot-weevil/build-$version/cljdoc.zip" -o "$archive"

    echo "Unpacking $archive"
    unzip "$archive" -d "cljdoc-$version"
    rm "$archive"
fi

pushd "cljdoc-$version"

echo "Starting process"
CLJDOC_PROFILE=prod CLJDOC_VERSION="$version" clojure -J-Xmx1400m -m cljdoc.server.system

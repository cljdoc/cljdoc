#!/usr/bin/env bash

set -euo pipefail

# TODO print instructions if file does not exist
version=$(cat "$HOME/CLJDOC_VERSION")
archive="cljdoc-$version.zip"

if [ ! -d "cljdoc-$version" ]; then
    echo "Downloading cljdoc v$version"
    uri="https://s3.amazonaws.com/cljdoc-releases-hot-weevil/build-$version/cljdoc.zip"
    echo $uri

    if ! curl --fail --location --silent "$uri" -o "$archive"; then
      echo "ERROR Failed to download build."
      exit 1
    fi

    echo "Unpacking $archive"
    unzip "$archive" -d "cljdoc-$version"
    rm "$archive"
fi

pushd "cljdoc-$version"

echo "Starting process"
CLJDOC_PROFILE=prod CLJDOC_VERSION="$version" CLJDOC_SECRETS="$HOME/secrets.edn" clojure -J-Xmx1400m -M -m cljdoc.server.system

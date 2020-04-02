#!/usr/bin/env bash

set -eou pipefail

cd $(git rev-parse --show-toplevel)

version=$(./script/version.sh)

git tag $version

pushd modules/deploy

clj -m cljdoc.deploy deploy -t $version

popd

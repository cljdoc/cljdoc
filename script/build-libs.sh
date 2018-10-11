#!/usr/bin/env bash

set -eou pipefail

if ! hash boot 2>/dev/null; then
  echo 'boot is required, please install first: https://github.com/boot-clj/boot';
  exit 1;
fi

pushd modules/shared-utils
boot -d seancorfield/boot-tools-deps deps pom -p cljdoc/shared-utils -v 0.1.0-SNAPSHOT jar install
popd

pushd modules/analysis-runner
boot -d seancorfield/boot-tools-deps deps pom -p cljdoc/analysis-runner -v 0.1.0-SNAPSHOT jar install
popd

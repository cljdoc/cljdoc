#!/usr/bin/env bash

set -eou pipefail

pushd modules/shared-utils
boot -d seancorfield/boot-tools-deps deps pom -p cljdoc/shared-utils -v 0.1.0-SNAPSHOT jar install
popd

pushd modules/analysis-runner
boot -d seancorfield/boot-tools-deps deps pom -p cljdoc/analysis-runner -v 0.1.0-SNAPSHOT jar install
popd

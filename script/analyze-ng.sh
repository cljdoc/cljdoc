#!/usr/bin/env bash
set -euo pipefail

echo "Running build for $1"
cd modules/analysis-runner
clojure -O:lambdawerk -m cljdoc.analysis.runner-ng "$1"

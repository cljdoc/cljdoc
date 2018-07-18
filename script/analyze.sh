#!/usr/bin/env bash
set -euo pipefail

project="$1"
version="$2"
jar_path="$3"
pom_path="$4"

echo "Running build for $project v$version"
echo "Jar: $jar_path"
echo "POM: $pom_path"

cd modules/analysis-runner

clojure \
    -m cljdoc.analysis.runner \
    "$project" "$version" "$jar_path" "$pom_path"

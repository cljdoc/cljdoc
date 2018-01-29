#!/usr/bin/env bash
set -euo pipefail

project="$1"
version="$2"
jar_path="$3"

curl -u "$CIRCLE_API_TOKEN:" \
     -d build_parameters[CLJDOC_PROJECT]="$project" \
     -d build_parameters[CLJDOC_PROJECT_VERSION]="$version" \
     -d build_parameters[CLJDOC_PROJECT_JAR]="$jar_path" \
     "$CIRCLE_BUILDER_ENDPOINT"

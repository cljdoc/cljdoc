#!/usr/bin/env bash
set -euo pipefail

project="$1"
version="$2"
jar_path="$3"
pom_path="$3"
cljdoc_version=$(git rev-parse HEAD)

# $CIRCLE_BUILDER_PROJECT must be Circle CI project set up
# with the configuration that can be found in the cljdoc-builder project
# https://github.com/martinklepsch/cljdoc-builder
# For the repo above the value should be "github/martinklepsch/cljdoc-builder"

curl -u "$CIRCLE_API_TOKEN:" \
     -d build_parameters[CLJDOC_ANALYZER_VERSION]="$cljdoc_version" \
     -d build_parameters[CLJDOC_PROJECT]="$project" \
     -d build_parameters[CLJDOC_PROJECT_VERSION]="$version" \
     -d build_parameters[CLJDOC_PROJECT_JAR]="$jar_path" \
     -d build_parameters[CLJDOC_PROJECT_POM]="$pom_path" \
     "https://circleci.com/api/v1.1/project/$CIRCLE_BUILDER_PROJECT/tree/master"

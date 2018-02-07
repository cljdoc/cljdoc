#!/usr/bin/env bash

project="$1"
version="$2"
jar_path="$3"

curl -u cljdoc:xxx \
     -d project="$project" \
     -d version="$version" \
     -d jarpath="$jar_path" \
     -i api.cljdoc.xyz:8000/request-build

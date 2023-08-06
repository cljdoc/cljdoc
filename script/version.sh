#!/bin/bash

set -eou pipefail

base_version=0.0
commit_count=$(git rev-list --count HEAD)
commit_sha=$(git rev-parse --short HEAD)
branch=$(git rev-parse --abbrev-ref HEAD)

if [ $branch = "master" ]; then
  echo "$base_version.$commit_count-$commit_sha"
else
  echo "$base_version.$commit_count-$branch-$commit_sha"
fi

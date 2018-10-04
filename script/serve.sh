#!/usr/bin/env bash

set -eo pipefail

# we use http-server from NPM here because python's
# SimpleHTTP doesn't like it when the directory it's
# serving dissapears every now and then
npx http-server target/grimoire-html/ -c-1 -o

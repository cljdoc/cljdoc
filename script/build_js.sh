#!/bin/sh

set -ex

# Remove the old compiled files
rm -rf resources-compiled/public/out/*

# Copy the static files that don't need hashing
npx \
  --yes \
  esbuild \
  --minify \
  --allow-overwrite \
  --entry-names='[name]' \
  --loader:.ico=copy \
  --loader:.svg=copy \
  --loader:.txt=copy \
  --loader:.html=copy \
  --outdir=resources-compiled/public/out \
  resources/public/static/*.* \
  resources/public/*.html

WATCH_ARG=""
if [ "$1" = "--watch" ]; then
  WATCH_ARG="--watch"
fi

# Bundle/minify JS and CSS and also copy SVG + PNG with hashes.
# Using exec so that the PID of the final process is the same
# as the PID of the script and all signals are forwarded to
# the final process.
exec \
  npx \
  --yes \
  esbuild \
  --target=es2022 \
  --bundle \
  --minify \
  --sourcemap \
  --allow-overwrite \
  $WATCH_ARG \
  --loader:.svg=copy \
  --loader:.png=copy \
  --entry-names='[name].[hash]' \
  --outdir=resources-compiled/public/out \
  resources/public/*.svg \
  resources/public/*.png \
  resources/public/*.css \
  js/cljdoc.tsx

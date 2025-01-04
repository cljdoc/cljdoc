#!/bin/sh

set -ex

# Remove the old compiled files
rm -rf resources-compiled/public/out/*

# Compile the JS
npx \
  --yes \
  esbuild \
  --bundle \
  js/index.tsx \
  --target=es2022 \
  --bundle \
  --sourcemap \
  --minify \
  --allow-overwrite \
  --entry-names='[dir]/[name].[hash]' \
  --outfile=resources-compiled/public/out/cljdoc.js

# Copy the static files that need hashing
npx \
  --yes \
  esbuild \
  --allow-overwrite \
  --entry-names='[dir]/[name].[hash]' \
  --loader:.css=copy \
  --loader:.svg=copy \
  --loader:.png=copy \
  --loader:.html=copy \
  --outdir=resources-compiled/public/out \
  resources/public/*.*

# Copy the CSS files and minify them
npx \
  --yes \
  esbuild \
  --minify \
  --allow-overwrite \
  --entry-names='[dir]/[name].[hash]' \
  --outdir=resources-compiled/public/out \
  resources/public/*.css

# Copy the static files that don't need hashing
npx \
  --yes \
  esbuild \
  --minify \
  --allow-overwrite \
  --entry-names='[dir]/[name]' \
  --loader:.ico=copy \
  --loader:.css=copy \
  --loader:.svg=copy \
  --loader:.png=copy \
  --loader:.html=copy \
  --outdir=resources-compiled/public/out \
  resources/public/static/*.*

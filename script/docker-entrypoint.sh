#!/bin/bash

# cljdoc web server launch script called from docker container

set -eou pipefail

echo "Preparing heap dump dir"
DATA_DIR="$(clojure -M script/get_cljdoc_data_dir.clj)"
HEAP_DUMP_DIR="${DATA_DIR}/heapdumps"
mkdir -p "${HEAP_DUMP_DIR}"

echo "Launching cljdoc server"
exec clojure \
  -J-Dcljdoc.host=0.0.0.0 \
  -J-XX:+HeapDumpOnOutOfMemoryError \
  -J-XX:HeapDumpPath="${HEAP_DUMP_DIR}" \
  -M -m cljdoc.server.system

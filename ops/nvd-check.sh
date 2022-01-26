#!/usr/bin/env bash
#
# This script was grabbed from: https://github.com/clojars/clojars-web/blob/main/bin/nvd-check
# and altered.
#
# Checks dependencies for CVEs using the NVD database. This script is based on
# instructions from https://github.com/rm-hull/nvd-clojure#clojure-cli-tool

set -euo pipefail

# Ensure that we will perform our check from cljdoc source root dir (one dir up from this script)
PROJECT_ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/../" && pwd)

# Install current version of nvd-clojure
clojure -Ttools install nvd-clojure/nvd-clojure '{:mvn/version "RELEASE"}' :as nvd

cd "$PROJECT_ROOT"

# When run in production, we use the cli alias, so replicate that here.
# Argument quoting is odd because quoting requirements for clojure cli tools is... odd.
# Config for nvd-clojure is slightly awkward:
# - we must specify config in a json file
# - the json file references suppresions which are in an xml file
clojure -J-Dclojure.main.report=stderr -Tnvd nvd.task/check \
 :classpath '"'"$(clojure -Spath -M:cli)"'"' \
 :config-filename '"./ops/nvd-config.json"'

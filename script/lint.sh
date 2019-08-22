#!/usr/bin/env bash

# Reasonable shortcut:
# cljdocs has a few small sub-projects under modules, we could lint those separately but
# instead we are linting all cljdoc source at once.

set -eou pipefail

function lint() {
    local lint_args
    if [ ! -d .clj-kondo/.cache ]; then
        echo "--[linting and building cache]--"
        # classpath with tests paths
        local classpath;classpath="$(clojure -R:test -C:test -Spath)"
        # include modules - but exclude shared-utils as it is already included in deps.edn
        local modules_paths;modules_paths=$(find modules -maxdepth 2 -mindepth 2 -name "src" | xargs -I {} dirname {} | grep -v "shared-utils")
        lint_args="$classpath $modules_paths --cache"
    else
        echo "--[linting]--"
        lint_args="src test modules"
    fi
    set +e
    clojure -A:clj-kondo --lint ${lint_args}
    local exit_code=$?
    set -e
    if [ ${exit_code} -ne 0 ] && [ ${exit_code} -ne 2 ] && [ ${exit_code} -ne 3 ]; then
        echo "** clj-kondo exited with unexpected exit code: ${exit_code}"
    fi
    exit ${exit_code}
}

lint

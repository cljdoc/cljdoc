#!/usr/bin/env bash

#
# We can turf this script (or at least get more raw) when either of the following is true:
# 1) We decide to stop employing unused destructured keys as documentation
# 2) clj-kondo supports ignoring this specific use of unused bindings
#
# Reasonable shortcut:
# cljdocs has a few small sub-projects under modules, we could lint those separately but
# instead we are linting all cljdoc source at once.

set -eou pipefail

function line_at() {
    local filename=$1
    local line_num=$2
    cat ${filename} | head -${line_num} | tail -1
}

function is_unused_destructured_key_warning() {
    local lint_line=$@
    local result="false"
    IFS=: read filename line_num col_num level msg <<< ${lint_line}
    if [[ ${level} =~ [[:space:]]*warning ]] && [[ ${msg} =~ [[:space:]]*unused[[:space:]]binding ]]; then
        local file_line=$(line_at $filename $line_num)
        if [[ ${file_line} =~ :keys ]]; then
            result="true"
        fi
    fi
    echo $result
}

function lint() {
    local out_file=$1
    local lint_args
    if [ ! -d .clj-kondo/.cache ]; then
        echo "--[linting and building cache]--"
        # classpath with tests paths
        local classpath="$(clojure -R:test -C:test -Spath)"
        # include modules - but exclude shared-utils as it is already included in deps.edn
        local modules_paths=$(find modules -name "*" -depth 1 | grep -v "shared-utils")
        lint_args="$classpath $modules_paths --cache"
    else
        echo "--[linting]--"
        lint_args="src test modules"
    fi
    set +e
    clojure -A:clj-kondo --lint ${lint_args} &> ${out_file}
    local exit_code=$?
    set -e
    if [ ${exit_code} -ne 0 ] && [ ${exit_code} -ne 2 ] && [ ${exit_code} -ne 3 ]; then
        cat ${out_file}
        echo "** clj-kondo exited with unexpected exit code: ${exit_code}"
        exit ${exit_code}
    fi
}

SCRATCH_DIR=$(mktemp -d -t clj-kondo.out.XXXXXXXXXX)
function cleanup() {
    rm -rf $SCRATCH_DIR
}
trap cleanup EXIT

EXIT_CODE=0
SUPPRESSED_COUNT=0

lint $SCRATCH_DIR/lint.out
while read lint_line; do
    if [[ $lint_line =~ ^linting[[:space:]]took ]]; then
        echo $lint_line
    elif  [ "$(is_unused_destructured_key_warning ${lint_line})" == "true" ]; then
        SUPPRESSED_COUNT=$((SUPPRESSED_COUNT+1))
    else
        EXIT_CODE=1
        echo $lint_line
    fi
done < $SCRATCH_DIR/lint.out

if [ ${SUPPRESSED_COUNT} -gt 0 ]; then
    echo "(suppressed ${SUPPRESSED_COUNT} unused descructured key warnings)"
fi

exit ${EXIT_CODE}

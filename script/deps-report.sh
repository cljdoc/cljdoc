#!/usr/bin/env bash

set -eou pipefail

# colors
if [ ! -z "${NO_COLOR:-}" ];then
  RED=""
  GREEN=""
  BLUE=""
  YELLOW=""
  COLOR_OFF=""
else
  RED='\e[0;31m'
  GREEN='\e[0;32m'
  BLUE='\e[0;94m'
  YELLOW='\e[0;33m'
  COLOR_OFF='\e[0m'
fi

function error_msg() {
    printf "${RED}✗ $1${COLOR_OFF}\n"
}

function info_msg() {
    printf "${BLUE}$1${COLOR_OFF}\n"
}

function success_msg() {
    printf "${GREEN}✔ $1${COLOR_OFF}\n"
}

function warn_msg() {
    printf "${YELLOW}★ $1${COLOR_OFF}\n"
}

function all_up_to_date_msg() {
    local component=$1
    success_msg "${component} - up to date"
}

function some_out_of_date_msg() {
    local component=$1
    local result_file=$2
    warn_msg "${component}:"
    cat ${result_file}
}

function show_result() {
    local component=$1
    local result_file=$2
    echo ""
    if [ -s "${result_file}" ];then
        some_out_of_date_msg ${component} "${result_file}"
    else
        all_up_to_date_msg ${component}
    fi
}

function polite_grep() {
    set +e
    grep "$@"
    set -e
}

function exclude_lines() {
    polite_grep "\S" | \
        polite_grep -v 'All up to date' | \
        polite_grep -v '^Downloading:' | \
        polite_grep -v "^SLF4J:"
}

function find_version() {
    sed 's/[^0-9.]*\([0-9.]*\).*/\1/'
}

# I don't see an option for olical/depot to check all aliases, so we need specify them
function get_aliases_option() {
    local aliases=$(clojure -e '(->> (slurp "deps.edn")
                                     (clojure.edn/read-string )
                                     :aliases
                                     (map #(name (first %)))
                                     (clojure.string/join ",")
                                     println)')
    if [ ! -z "${aliases}" ]; then
        echo "--aliases ${aliases}"
    fi
}

function scratch_file() {
    local fname=$(echo $1 | tr '/' '_')
    echo "${SCRATCH_DIR}/${fname}"
}

function clj_outdated_cmd() {
    local deps_file=$1
    # We don't want to repeat olical/depot in each deps.edn so we manually bring in version from main deps.edn
    # Also bring in version of tools.deps.alpha olical/depot requires - this is temporary and should be deletable
    # after all deps.edn are upgraded.
    local depot_version=$(grep "olical/depot" deps.edn | find_version)
    (cd $(dirname ${deps_file})
     local depot_dep="{olical/depot {:mvn/version \"${depot_version}\"}"
     local tools_deps_dep="org.clojure/tools.deps.alpha {:mvn/version \"0.7.516\"}"
     clojure -Sdeps "{:deps ${depot_dep} ${tools_deps_dep}}}" \
             -m depot.outdated.main \
             $(get_aliases_option))
}

function clj_outdated() {
    local deps_file=$1
    local result_file=$(scratch_file ${deps_file})
    clj_outdated_cmd ${deps_file} 2>&1 | exclude_lines > ${result_file}
    show_result ${deps_file} ${result_file}
}

function check_clj() {
    clj_outdated "deps.edn"
    while read deps_file; do
        clj_outdated ${deps_file}
    done < <(find modules -name "deps.edn")
}

function check_node() {
    local result_file=$(scratch_file "node")
    set +e
    npm outdated &> ${result_file}
    set -e
    show_result "package.json" "${result_file}"
}

function check_hardcoded_version() {
    local npm_libname=$1
    local current=$(npm view ${npm_libname} version)
    local available=$(npm view ${npm_libname} dist-tags.latest)
    if [ -z "${available}" ]; then
        error_msg "${npm_libname} - unable to find via npm view"
    elif [ ! "${current}" == "${available}" ]; then
        echo "${npm_libname} - v${available} is available"
    fi
}

function check_hardcoded() {
    local ns="cljdoc.render.external-libs"
    local result_file=$(scratch_file "hardcoded")
    touch ${result_file}
    local npm_libs=$(clojure -e "(require '[${ns} :as l]) (println (l/get-npm-lib-versions))")
    for npm_libname in ${npm_libs}; do
        local lib_result=$(check_hardcoded_version ${npm_libname})
        if [ ! -z "${lib_result}" ];then
            echo "${lib_result}" >> ${result_file}
        fi
    done
    show_result "${ns}" "${result_file}"
}

function manual_review() {
    echo ""
    warn_msg "manual review is currently required for:"
    echo ".circleci/config.yml"
    echo "modules/analysis-runner/src/cljdoc/analysis/deps.clj"
    echo "ops/docker/Dockerfile"

}
SCRATCH_DIR=$(mktemp -d -t clj-kondo.out.XXXXXXXXXX)
function cleanup() {
    rm -rf $SCRATCH_DIR
}
trap cleanup EXIT

info_msg "cljdoc dependencies report"
check_clj
check_node
check_hardcoded
manual_review
info_msg "\nThis report is informational only. Update what makes sense when it makes sense."

#TODO: consider after asciidoctor merge
# we don't check fontawesome because we are pinned to v4.x for asciidoctor
# we don't check for ruby sass versions because these are hardcoded to asciidoctor...  it would be interesting to know though
# if there is a newer version of assciidoctor-stylesheet generator,

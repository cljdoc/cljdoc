#!/bin/bash

# A wrapper around validate-cljdoc-edn.clj that ensures `clojure` is
# available and downloads the validate-cljdoc-edn.clj file from GitHub

set -eou pipefail

function ensure_clojure(){
    if ! hash clojure 2>/dev/null; then
        curl -O https://download.clojure.org/install/linux-install-1.9.0.397.sh
        chmod +x linux-install-1.9.0.397.sh
        sudo ./linux-install-1.9.0.397.sh
    fi
}

rev="cljdoc-edn-validator" # TODO replace with SHA
filename="validate-cljdoc-edn.clj"
uri="https://raw.githubusercontent.com/cljdoc/cljdoc/$rev/script/$filename"

ensure_clojure

curl -sOL $uri

if ! clojure $filename; then
    rm $filename
    echo "Problems found"
    exit 1
else
    rm $filename
fi

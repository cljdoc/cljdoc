#!/bin/bash

[ -z "$CLOJARS_USERNAME" ] && echo "Running this without \$CLOJARS_USERNAME set won't work" && exit 1
[ -z "$CLOJARS_PASSWORD" ] && echo "Running this without \$CLOJARS_PASSWORD set won't work" && exit 1

id="cljdoc"
scm="https://github.com/cljdoc/cljdoc"
alias=":jar-deploy"
jarpath="target/cljdoc.jar"
version=$(./script/version.sh)

echo "# Generate pom.xml"
clj -A$alias -m garamond.main --artifact-id $id --group-id $id --scm-url $scm --pom --force-version $version

echo "# Generate $jarpath"
clj -A$alias -m mach.pack.alpha.skinny --no-libs --project-path $jarpath

# echo "# Deploy to Clojars"
clj -A$alias -m deps-deploy.deps-deploy deploy $jarpath

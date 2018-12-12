#!/usr/bin/env bash

set -eou pipefail

if [[ $# -eq 0 ]] || [ -z "$1" ] ; then
    echo 'No version supplied, aborting'
    exit 1
fi

version="$1"

if ! curl --silent --fail --head "https://s3.amazonaws.com/cljdoc-releases-hot-weevil/build-$version/cljdoc.zip";
then
  echo "ERROR: Requested version not available on S3: $version"
  exit 1
fi

tf_out () {
  git_root=$(git rev-parse --show-toplevel)
  terraform output -state="$git_root/ops/infrastructure/terraform.tfstate" $1
}

api_ip=$(tf_out api_ip)
tf_out_json=$(tf_out -json)

secrets_file=$(mktemp -t cljdoc-secrets.edn)
version_file=$(mktemp -t CLJDOC_VERSION)

echo -e "\nDeploying $version to $api_ip"

echo "$tf_out_json" | ./ops/create-secrets.cljs > "$secrets_file"
echo -e "\nSecrets written to $secrets_file"
echo "$version" > "$version_file"

echo -e "\nDeploying secrets file..."
scp "$secrets_file" "cljdoc@$api_ip:secrets.edn"
echo -e "Deleting secrets file..."
rm "$secrets_file"

echo -e "\nDeploying CLJDOC_VERSION file..."
scp "$version_file" "cljdoc@$api_ip:CLJDOC_VERSION"

echo -e "\nDeploying runner file..."
scp "ops/run-cljdoc-api.sh" "cljdoc@$api_ip:"
ssh "cljdoc@$api_ip" chmod +x run-cljdoc-api.sh

echo -e "\nFiles updated, restarting..."

ssh "root@$api_ip" systemctl restart cljdoc-api

echo -e "\nTagging release..."
git tag -f live "$version"
git push --tags -f

echo -e "\nDone"

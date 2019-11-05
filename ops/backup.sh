#!/bin/bash

set -eou pipefail

git_root=$(git rev-parse --show-toplevel)
local_backups="$git_root/ops/prod-backup"

tf_out () {
  terraform output -state="$git_root/ops/infrastructure/terraform.tfstate" $1
}

ip=$(tf_out main_ip)
date=$(date "+%Y-%m-%d")
tar_file="backup-$date.tar.gz"


ssh root@$ip rm -rf /tmp/cljdoc-backup/
ssh root@$ip mkdir /tmp/cljdoc-backup/

ssh root@$ip sqlite3 /data/cljdoc/cljdoc.db.sqlite \".backup '/tmp/cljdoc-backup/cljdoc.db.sqlite'\"

ssh root@$ip tar -cazf "$tar_file" -C /tmp/cljdoc-backup/ .

entry_count=$(ssh root@$ip tar -tf "$tar_file" | wc -l)

echo "Stored $entry_count files in $tar_file"

scp root@$ip:"$tar_file" "$local_backups"
ssh root@$ip rm "$tar_file"
ssh root@$ip rm -rf /tmp/cljdoc-backup

echo "Syncing to S3"

AWS_ACCESS_KEY_ID=$(tf_out backups_bucket_user_access_key) \
  AWS_SECRET_ACCESS_KEY=$(tf_out backups_bucket_user_secret_key) \
  aws s3 sync --storage-class "STANDARD_IA" "$local_backups" s3://$(tf_out backups_bucket_name)/

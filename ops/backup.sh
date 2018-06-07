#!/bin/bash

set -eou pipefail

ip=$(terraform output api_ip)
date=$(date "+%Y-%m-%d")
file="backup-$date.tar.gz"

ssh root@$ip tar -cazf "$file" -C /var/cljdoc/ .

entry_count=$(ssh root@$ip tar -tf "$file" | wc -l)

echo "Stored $entry_count files in $file"

scp root@$ip:"$file" prod-backup/
ssh root@$ip rm "$file"

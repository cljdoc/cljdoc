#!/bin/bash

set -eou pipefail

file="$1"
ip=$(terraform output api_ip)
restore_target="/var/cljdoc2/"

data_dir_exists=$(ssh root@$ip test -d "$restore_target"; echo $?)

if [ "$data_dir_exists"  -eq  0 ]
then
    echo "Can't restore, found existing $restore_target"
else
    echo "Uploading $file ..."
    scp "$file" root@$ip:"restore.tar.gz"
    ssh root@$ip mkdir "$restore_target"
    ssh root@$ip tar -xf "restore.tar.gz" -C "$restore_target"
    echo "Restored" $(ssh root@$ip find "$restore_target" | wc -l) "files"
fi

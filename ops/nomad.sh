#!/bin/bash

set -eou pipefail

tf_out () {
  git_root=$(git rev-parse --show-toplevel)
  terraform output -state="$git_root/ops/infrastructure/terraform.tfstate" $1
}

ip=$(tf_out main_ip)

socket=$(mktemp -t deploy-ssh-socket)
rm ${socket} # delete socket file so path can be used by ssh

exit_code=0

cleanup () {
    # Stop SSH port forwarding process, this function may be
    # called twice, so only terminate port forwarding if the
    # socket still exists
    if [ -S ${socket} ]; then
        echo
        echo "Sending exit signal to SSH process"
        ssh -S ${socket} -O exit root@${ip}
    fi
    exit $exit_code
}

trap cleanup EXIT ERR INT TERM

# Start SSH port forwarding process for consul (8500) and nomad (4646)
ssh -M -S ${socket} -fNT -L 8500:localhost:8500 -L 4646:localhost:4646 root@${ip}

ssh -S ${socket} -O check root@${ip}

echo "You can now open Nomad or Consul:"
echo "Nomad: http://localhost:4646/"
echo "Consul: http://localhost:8500/"

bash --rcfile <(echo 'PS1="\nnomad> "')

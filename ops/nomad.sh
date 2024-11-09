#!/bin/bash

# WHAT
# This script can be used to launch an SSH process forwarding nomad and consul ports
# to localhost. The script also launches a subshell with an adjusted `nomad>` prompt.
# The port forwarding is terminated when the subshell exits.
# This allows users to communicate with the Nomad server using their local `nomad`
# binary. Ex.: `nomad status cljdoc`, `nomad alloc logs -f $alloc_id`, and so on.

# HOW
# It is largely inspired by http://mpharrigan.com/2016/05/17/background-ssh.html
# An SSH process is launched to forward Nomad and Consul ports and backgrounded
# That process uses a tmp-file as socket which allows us to later terminate the
# forwarding by via `-O exit`.
# To ensure the SSH process is always terminated `trap` is used to run `-O exit`
# when the process exits, fails or is interrupted

set -eou pipefail

# Ensure a single argument is passed in the format username@ip
if [ $# -ne 1 ]; then
  echo "Usage: $0 <username@ip>"
  exit 1
fi

user_ip=$1

# linux requires the Xs
socket=$(mktemp -t deploy-ssh-socket-XXXXXX)
rm "${socket}" # delete socket file so path can be used by ssh

exit_code=0

cleanup () {
    # Stop SSH port forwarding process, this function may be
    # called twice, so only terminate port forwarding if the
    # socket still exists
    if [ -S "${socket}" ]; then
        echo
        echo "Sending exit signal to SSH process"
        ssh -S "${socket}" -O exit "${user_ip}"
    fi
    exit $exit_code
}

trap cleanup EXIT ERR INT TERM

# Start SSH port forwarding process for consul (8500) and nomad (4646)
ssh -M -S "${socket}" -fNT \
    -L 8080:localhost:8080 \
    -L 8500:localhost:8500 \
    -L 4646:localhost:4646 \
    -L 9010:localhost:9010 \
    "${user_ip}"

ssh -S "${socket}" -O check "${user_ip}"

echo "You can now open Nomad or Consul:"
echo "Nomad: http://localhost:4646/"
echo "Consul: http://localhost:8500/"
echo "Traefik: http://localhost:8080/"
echo "SSH: ssh ${user_ip}"

bash --rcfile <(printf 'PS1="\nnomad> "')

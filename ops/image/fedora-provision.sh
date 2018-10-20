#!/usr/bin/env bash

set -euo pipefail

username="cljdoc"
prod_data_dir="/var/cljdoc"

dnf update -y

dnf install -y git curl tmux htop nginx certbot-nginx sqlite java-9-openjdk rlwrap unzip

# User setup

useradd "$username"

# Fix permissions on files uploaded as part of provisioning
# chown "$username:$username" \
#       "/home/$username/CLJDOC_VERSION" \
#       "/home/$username/run-cljdoc-api.sh"

# SSH --------------------------------------------------------------------------
mkdir /home/$username/.ssh
curl -s "https://github.com/martinklepsch.keys" >> /home/$username/.ssh/authorized_keys
chmod 700 /home/$username/.ssh
chmod 600 /home/$username/.ssh/authorized_keys
chown -R $username:$username /home/$username/.ssh

# SUDO -------------------------------------------------------------------------
# usermod cljdoc -a -G wheel
# echo "$username ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Disable SSH PasswordAuthentication
# This will lock out the root user unless a pubkey has been added to it's authorized_keys file
# TODO actually it does not, see https://www.digitalocean.com/community/questions/why-is-there-passwordauthentication-yes-in-sshd_config-even-though-i-changed-it-when-creating-the-image
# echo "Disabling SSH PasswordAuthentication"
# sed -i '/PasswordAuthentication yes/c\PasswordAuthentication no' /etc/ssh/sshd_config
# cat /etc/ssh/sshd_config

# Clojure CLI Tools -------------------------------------------------------------

curl -O https://download.clojure.org/install/linux-install-1.9.0.397.sh
chmod +x linux-install-1.9.0.397.sh
sudo ./linux-install-1.9.0.397.sh

# Nginx

# https://unix.stackexchange.com/questions/196907/proxy-nginx-shows-a-bad-gateway-error
setsebool -P httpd_can_network_connect true
mkdir /etc/nginx/sites-enabled/
mkdir /etc/nginx/sites-available/

# Cleanup -----------------------------------------------------------------------

dnf clean all

mkdir -p $prod_data_dir
chown -R $username:$username $prod_data_dir

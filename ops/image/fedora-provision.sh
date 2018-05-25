#!/usr/bin/env bash

set -euo pipefail

username="cljdoc"
prod_data_dir="/var/cljdoc"

jdk_rpm="/tmp/jdk.rpm"
curl -L -o "$jdk_rpm" \
     -H "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" \
     "http://download.oracle.com/otn-pub/java/jdk/8u172-b11/a58eab1ec242421181065cdc37240b08/jdk-8u172-linux-x64.rpm"

dnf install -y "$jdk_rpm"
rm "$jdk_rpm"

dnf update -y

dnf install -y git curl tmux htop nginx certbot-nginx sqlite

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

# Boot installation -------------------------------------------------------------

bootpath=/usr/local/bin/boot
curl -sL "https://github.com/boot-clj/boot-bin/releases/download/2.7.2/boot.sh" -o "$bootpath"
chmod +x "$bootpath"

# Nginx

# https://unix.stackexchange.com/questions/196907/proxy-nginx-shows-a-bad-gateway-error
setsebool -P httpd_can_network_connect true
mkdir /etc/nginx/sites-enabled/
mkdir /etc/nginx/sites-available/

# Cleanup -----------------------------------------------------------------------

dnf clean all

mkdir -p $prod_data_dir
chown -R $username:$username $prod_data_dir

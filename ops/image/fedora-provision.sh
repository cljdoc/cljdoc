#!/usr/bin/env bash

set -euo pipefail

username="cljdoc"

dnf update -y

dnf install -y git curl tmux htop

jdk_rpm="/tmp/jdk.rpm"
curl -L -o "$jdk_rpm" \
     -H "Cookie: gpw_e24=http%3A%2F%2Fwww.oracle.com%2F; oraclelicense=accept-securebackup-cookie" \
     "http://download.oracle.com/otn-pub/java/jdk/8u162-b12/0da788060d494f5095bf8624735fa2f1/jdk-8u162-linux-x64.rpm"

dnf install -y "$jdk_rpm"
rm "$jdk_rpm"

# User setup

useradd "$username"

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

# Boot installation

bootpath=/usr/local/bin/boot
curl -sL "https://github.com/boot-clj/boot-bin/releases/download/2.7.2/boot.sh" -o "$bootpath"
chmod +x "$bootpath"

dnf clean all

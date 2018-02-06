#!/usr/bin/env bash

set -euo pipefail

username="cljdoc"
add-apt-repository -y ppa:webupd8team/java

apt-get update
apt-get upgrade -y
apt-get install -y git curl

apt autoremove -y
apt-get install -y binutils
echo -e "\n\n\ndpkg --configure -a"
dpkg --configure -a  # without this there's some weird 'held packages' error when installing JDK

echo -e "\n\n\nAccept Java agreement"
echo "oracle-java8-installer shared/accepted-oracle-license-v1-1 select true" | debconf-set-selections
echo -e "\n\n\nInstall java"
apt-get install -y oracle-java8-installer

# User setup

adduser --disabled-password --gecos "" "$username"
usermod -aG sudo "$username"

# SSH
mkdir /home/$username/.ssh

curl -s "https://github.com/martinklepsch.keys" >> /home/$username/.ssh/authorized_keys

chmod 700 /home/$username/.ssh
chmod 600 /home/$username/.ssh/authorized_keys
chown -R $username:$username /home/$username/.ssh

# allow passwordless sudo
# echo "$username ALL=(ALL) NOPASSWD:ALL" >> /etc/sudoers

# Disable SSH PasswordAuthentication
# This will lock out the root user unless a pubkey has been added to it's authorized_keys file
# TODO actually it does not, see https://www.digitalocean.com/community/questions/why-is-there-passwordauthentication-yes-in-sshd_config-even-though-i-changed-it-when-creating-the-image
echo "Disabling SSH PasswordAuthentication"
sed -i '/PasswordAuthentication yes/c\PasswordAuthentication no' /etc/ssh/sshd_config
cat /etc/ssh/sshd_config

# Consider adding Firewall setup
# https://www.digitalocean.com/community/tutorials/initial-server-setup-with-ubuntu-16-04#step-seven-%E2%80%94-set-up-a-basic-firewall


# Clojure Setup

bootpath=/usr/local/bin/boot
curl -sL "https://github.com/boot-clj/boot-bin/releases/download/2.7.2/boot.sh" -o "$bootpath"
chmod +x "$bootpath"

# su -c 'BOOT_VERSION=2.7.2 boot repl' - "$username"

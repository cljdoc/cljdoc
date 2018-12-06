#!/usr/bin/env bash

set -eou pipefail

domain="cljdoc.org"

echo "--- Getting .org Certificate -------------------------------------------------------"

certbot certonly -m martinklepsch@gmail.com -d "$domain" --nginx --agree-tos --non-interactive

echo "--- Linking .org Server Block ------------------------------------------------------"

ln -s "/etc/nginx/sites-available/$domain.conf" "/etc/nginx/sites-enabled/$domain.conf"

domain="cljdoc.xyz"

echo "--- Getting .xyz Certificate -------------------------------------------------------"

certbot certonly -m martinklepsch@gmail.com -d "$domain" --nginx --agree-tos --non-interactive

echo "--- Linking .xyz Server Block ------------------------------------------------------"

ln -s "/etc/nginx/sites-available/$domain.conf" "/etc/nginx/sites-enabled/$domain.conf"

echo "--- Restarting Nginx ----------------------------------------------------------"

systemctl restart nginx

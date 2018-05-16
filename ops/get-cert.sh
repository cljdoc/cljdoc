#!/usr/bin/env bash

set -eou pipefail

domain="cljdoc.xyz"

echo "--- Getting Certificate -------------------------------------------------------"

certbot certonly -m martinklepsch@gmail.com -d "$domain" --nginx --agree-tos --non-interactive

echo "--- Linking Server Block ------------------------------------------------------"

ln -s "/etc/nginx/sites-available/$domain.conf" "/etc/nginx/sites-enabled/$domain.conf"

echo "--- Restarting Nginx ----------------------------------------------------------"

systemctl restart nginx

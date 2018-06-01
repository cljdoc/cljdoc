#!/usr/bin/env bash

set -eou pipefail

email="martinklepsch@gmail.com"
domain="cljdoc.xyz"

echo "--- Getting Certificate -------------------------------------------------------"

export AWS_ACCESS_KEY_ID="FIXME"
export AWS_SECRET_ACCESS_KEY="FIXME"

# certbot certonly -m "$email" -d "$domain" --nginx --agree-tos --non-interactive

# Using certbot with route53 requires building certbot manually
# certbot certonly -m "$email" -d "$domain" --dns-route53 --agree-tos --non-interactive

# So here we go and use their docker images -----------------------------------------

# Permissions can be fixed by appending :Z
# https://stackoverflow.com/a/34537509
# -v /tmp/le:/tmp/:Z

# TODO figure out if this line is necessary:
# -v /etc/letsencrypt-lib/:/var/lib/letsencrypt-d \

docker run -it --rm \
       --name certbot \
       -v /tmp/letsencrypt:/etc/letsencrypt/:Z \
       -v /etc/letsencrypt-lib/:/var/lib/letsencrypt-d \
       -e AWS_ACCESS_KEY_ID="$AWS_ACCESS_KEY_ID" \
       -e AWS_SECRET_ACCESS_KEY="$AWS_SECRET_ACCESS_KEY" \
       certbot/dns-route53 certonly -m "$email" --dns-route53 -d "$domain" --agree-tos --non-interactive

echo "--- Moving certs in right place -----------------------------------------------"

mv "/tmp/letsencrypt/live/$domain" /etc/letsencrypt/live/

echo "--- Clean up docker mount -----------------------------------------------------"

rm -rf /tmp/letsencrypt

echo "--- Restarting Nginx ----------------------------------------------------------"

# TODO fix this

systemctl restart nginx

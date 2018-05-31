#!/bin/bash

set -eou pipefail

ssh root@cljdoc.xyz cat /var/log/nginx/cljdoc-access.log \
    | goaccess -c \
               --log-format='%h %^[%d:%t %^] "%r" %s %b "%R" "%u" %^ %T' \
               --date-format '%d/%b/%Y' \
               --time-format '%H:%M:%S' -

FROM nginx

RUN rm /etc/nginx/conf.d/default.conf

COPY cljdoc.xyz.conf /etc/nginx/sites-enabled/
COPY nginx.conf /etc/nginx
COPY 5xx.html /usr/share/nginx/html

VOLUME /etc/nginx
VOLUME /var/log/nginx

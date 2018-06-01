# Nginx docker setup

This has been created in order to be able to easily test Nginx configuration locally.

Useful commands:

```sh
# build the image
docker build -t cljdoc-nginx .

# run the image 
docker run --name cljdoc-nginx -p 80:80 -p 443:443 -d --rm cljdoc-nginx
```


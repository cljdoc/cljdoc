# Nginx docker setup

This has been created in order to be able to easily test Nginx configuration locally.

Useful commands:

```sh
# build the image
docker build -t cljdoc-nginx .

# run the image 
docker run --name cljdoc-nginx -p 80:80 -p 443:443 -d --rm cljdoc-nginx
```

- [ ] use terraform to create a user with [this policy](https://github.com/certbot/certbot/blob/master/certbot-dns-route53/examples/sample-aws-policy.json) for DNS based certbot auth
- [ ] figure out how to provide AWS creds to `get-cert.sh` and run it on the instance
- [ ] some lines in cljdoc.xyz.conf cause nginx to fall over (ssl related)
- [ ] it's not clear to me yet how to access logs of nginx cljdoc server block

### Certificates

Getting certificates with nginx running in docker is different and I
couldn't quite get it to work.  Somewhere down this road I discovered
that certbot provides DNS challenge plugins as Docker containers and
because this works without any running web server it's significantly
easier to get a certificate.

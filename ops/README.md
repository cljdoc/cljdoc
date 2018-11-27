# cljdoc Operations

This directory contains a [Terraform](https://www.terraform.io/)
configuration to create infrastructure required by cljdoc.

It also contains a [Packer](https://www.packer.io/) configuration
to create machine images for the cljdoc server.

> The intention here is not just to make it easy to spin up an
> environment but also to document what is needed.

[Terraform](#terraform) | [Packer](#packer) | [Backups](#backing-up-data) | [Deployment](#deployment)

## Required Secrets

At least an AWS Access- and Secret key is required as well as a PGP key to
encrypt secrets created during the process of terraforming. 🙂

```bash
export TF_VAR_aws_access_key_id=foo
export TF_VAR_aws_secret_key=foo
export TF_VAR_do_token=digital-ocean-api-token
```

## Terraform

### What does this do?

Creating various resources on AWS and DigitalOcean:

- an S3 bucket for cljdoc releases (+ user & key)
- an S3 bucket for cljdoc backups (+ user & key)
- a DigitalOcean droplet to run cljdoc
- a Route53 HostedZone for domain supplied in the configuration
- a HostedZone RecordSet to point the domain to the droplet

The used domain can be customized in `cljdoc.tfvars`. The domain will
not be bought and it is expected that the domain's nameserver are set
to the nameservers of the Route53 HostedZone, which are part of the outputs.

### Common Commands

**Note:** These commands require the environment variables mentioned above to be set.

```
terraform plan -var-file cljdoc.tfvars
terraform apply -var-file cljdoc.tfvars
terraform refresh -var-file cljdoc.tfvars
```

Retrieving outputs:
```
terraform output
terraform output -json
```

As part of the terraform setup `run-cljdoc-api.sh` will be uploaded to the server.
This script expects a `CLJDOC_VERSION` file in the `$HOME` directory of the user running
it which contains a full shasum of a commit available on Github. A full deploy routine can
be found in `deploy.sh`:

```sh
./ops/deploy.sh (git rev-parse origin/master)
```

### Certificates

To get a certificate run:

```sh
ssh root@(terraform output api_ip) bash < ./get-cert.sh
```


## Packer

We use Packer to create machine images for the cljdoc server.
All required files can be found in `./image/`

#### Creating an image

This requires `TF_VAR_do_token` to be set.

```sh
cd image
make image-id
```
This will create an image and the image-id will be saved to `image-id`.
The image is based on Fedora, additional provisioning steps can be found in `fedora-provision.sh`.

## Backing Up Data

See `backup.sh` and `restore.sh`.

## Deployment

- `script/package` runs various build steps and packages everything into a zip file.
- CircleCI uploads that zip file to an S3 bucket (which is part of the Terraform setup)
- `ops/run-cljdoc-api.sh` downloads this zip file and run it in the `prod` profile (see `resources/config.edn` for what this means specifically)

## Migrating Hosts

When the image is updated it is expected that cljdoc is moved to a new host. Below is
a rough checklist what stuff should be done when doing so.

- [ ] Create new image
  - [ ] Create server from that image
  - [ ] Deploy to that server, check if dependencies are met
  - [ ] Shut down test server

- [ ] Stop service on current server
- [ ] Backup data
- [ ] Restore data to production server
- [ ] Start cljdoc service on new server
- [ ] Swap IP in R53 record
- [ ] Generate certs (or copy if necessary)
- [ ] link sites-enabled server block (see `get-certs.sh`)


# Nomad / Consul notes and questions

- What address to bind to for single instance cluster?

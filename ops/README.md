# cljdoc Operations

This directory contains a [Terraform](https://www.terraform.io/)
configuration to create infrastructure required by cljdoc.

It also contains a [Packer](https://www.packer.io/) configuration
to create machine images for the cljdoc server.

> The intention here is not just to make it easy to spin up an
> environment but also to document what is needed.

## Required Secrets

At least an AWS Access- and Secret key is required as well as a PGP key to
encrypt secrets created during the process of terraforming. 🙂

```bash
export TF_VAR_aws_access_key_id=foo
export TF_VAR_aws_secret_key=foo
export TF_VAR_do_token=digital-ocean-api-token
export TF_VAR_pgp_key=base64-encoded-public-key
```

To base64 encode your public key you can run:

```bash
gpg --export YOUR_KEY_ID | base64
```

## Terraform

### What does this do?

Creating various resources on AWS and DigitalOcean:

- an S3 bucket where HTML documentation is stored
- a user with a policy that allows updating the bucket contents
  - a keypair for the user
- a Cloudfront distribution that serves the contents of this bucket
- a Route53 HostedZone for domain supplied in the configuration
- a HostedZone RecordSet to point the domain to the Cloudfront distribution
- a Droplet to run the cljdoc API

For the Cloudfront distribution an AWS ACM certificate will be used which needs to be created manually.

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
terraform output bucket_user_secret_key | base64 --decode | gpg -q --decrypt
```

As part of the terraform setup `run-cljdoc-api.sh` will be uploaded to the server.
This script expects a `CLJDOC_VERSION` file in the `$HOME` directory of the user running
it which contains a full shasum of a commit available on Github. Create it using SSH:

```sh
ssh cljdoc@(terraform output api_ip) "echo 5471...436 > CLJDOC_VERSION"
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

The provisioning script can be found in `provision.sh`.

Currently the provisioning script locks out the `root` user
and does not enable `sudo` for the `cljdoc` user. This means all
steps requiring admin-privileges need to be done at build time.
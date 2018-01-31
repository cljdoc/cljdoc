# cljdoc Operations

This directory contains a [Terraform](https://www.terraform.io/)
configuration to create infrastructure required by cljdoc.

> The intention here is not just to make it easy to spin up an
> environment but also to document what is needed.

## Required Secrets

At least an AWS Access- and Secret key is required as well as a PGP key to
encrypt secrets created during the process of terraforming. 🙂

```bash
export TF_VAR_aws_access_key_id=foo
export TF_VAR_aws_secret_key=foo
export TF_VAR_pgp_key=base64-encoded-public-key
```

To base64 encode your public key you can run:

```bash
gpg --export YOUR_KEY_ID | base64
```

## What does this do?

Mostly creating things on AWS:

- an S3 bucket where HTML documentation is stored
- a user with a policy that allows updating the bucket contents
  - a keypair for the user
- a Cloudfront distribution that serves the contents of this bucket
- a Route53 HostedZone for domain supplied in the configuration
- a HostedZone RecordSet to point the domain to the Cloudfront distribution

For the Cloudfront distribution an AWS ACM certificate will be used which needs to be created manually.

The used domain can be customized in `cljdoc.tfvars`. The domain will
not be bought and it is expected that the domain's nameserver are set
to the nameservers of the Route53 HostedZone, which are part of the outputs.

## Common Commands

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

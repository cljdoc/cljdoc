# Providers ----------------------------------------------------------

provider "aws" {
  alias = "prod"

  region     = "${var.aws_region}"
  access_key = "${var.aws_access_key_id}"
  secret_key = "${var.aws_secret_key}"
}

provider "digitalocean" {
  token = "${var.do_token}"
}

# S3 Buckets ---------------------------------------------------------
# Todo consider not parameterizing those with the pet name stuff

module "releases_bucket" {
  source      = "./public_bucket"
  bucket_name = "${var.releases_bucket_name}"
}

module "backups_bucket" {
  source      = "./public_bucket"
  bucket_name = "${var.backups_bucket_name}"
}

# DigitalOcean Server ------------------------------------------------

resource "digitalocean_droplet" "cljdoc_api_old" {
  image      = "34859710" # ${file("image/image-id")}
  name       = "cljdoc-1"
  region     = "ams3"
  size       = "2gb"
  monitoring = true

  # supplying a key here seems to be the only way to
  # not get a root password via email, despite having
  # added SSH keys to the snapshot/image before
  ssh_keys = ["18144068"]
}

resource "digitalocean_droplet" "cljdoc_api" {
  image      = "${file("image/image-id")}"
  name       = "cljdoc-2"
  region     = "ams3"
  size       = "s-1vcpu-2gb"
  monitoring = true

  # supplying a key here seems to be the only way to
  # not get a root password via email, despite having
  # added SSH keys to the snapshot/image before
  ssh_keys = ["18144068"]
}

# Route53 ------------------------------------------------------------

resource "aws_route53_zone" "cljdoc_xyz_zone" {
  provider = "aws.prod"
  name     = "${var.xyz_domain}"
}

resource "aws_route53_record" "main" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_xyz_zone.zone_id}"
  name     = "${var.xyz_domain}"
  type     = "A"
  ttl      = "300"
  records  = ["${digitalocean_droplet.cljdoc_api.ipv4_address}"]
}

resource "aws_route53_record" "dokku" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_xyz_zone.zone_id}"
  name     = "clojars-stats.${var.xyz_domain}"
  type     = "A"
  ttl      = "300"
  records  = ["167.99.133.5"]
}

# Org zone and records

resource "aws_route53_zone" "cljdoc_org_zone" {
  provider = "aws.prod"
  name     = "${var.org_domain}"
}

resource "aws_route53_record" "cljdoc_org_main" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_org_zone.zone_id}"
  name     = "${var.org_domain}"
  type     = "A"
  ttl      = "300"
  records  = ["${digitalocean_droplet.cljdoc_api.ipv4_address}"]
}

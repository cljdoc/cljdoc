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

# DigitalOcean Server 2.0 --------------------------------------------

module "main_server" {
  source     = "./server_instance"
  image_id   = "${file("../image/nomad-image-id")}"
  org_zone   = "${aws_route53_zone.cljdoc_org_zone.zone_id}"
  xyz_zone   = "${aws_route53_zone.cljdoc_xyz_zone.zone_id}"
  org_domain = "cljdoc.org"
  xyz_domain = "cljdoc.xyz"
}

# Route53 ------------------------------------------------------------

resource "aws_route53_zone" "cljdoc_xyz_zone" {
  provider = aws
  name     = "${var.xyz_domain}"
}

resource "aws_route53_zone" "cljdoc_org_zone" {
  provider = "aws.prod"
  name     = "${var.org_domain}"
}

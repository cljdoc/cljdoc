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

# Random -------------------------------------------------------------

resource "random_pet" "server" {
  # https://www.terraform.io/docs/providers/random/r/pet.html
  keepers = {
    # Generate a new pet name each time we switch to a new domain
    domain = "${var.domain}"
  }
}

# Policies -----------------------------------------------------

module "releases_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-releases-${random_pet.server.id}"
}

module "backups_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-backups-${random_pet.server.id}"
}

# DigitalOcean Server ------------------------------------------------

resource "digitalocean_droplet" "cljdoc_api" {
  image      = "${file("image/image-id")}"
  name       = "cljdoc-1"
  region     = "ams3"
  size       = "2gb"
  monitoring = true

  # supplying a key here seems to be the only way to
  # not get a root password via email, despite having
  # added SSH keys to the snapshot/image before
  ssh_keys = ["18144068"]
}

# Route53 ------------------------------------------------------------

resource "aws_route53_zone" "cljdoc_zone" {
  provider = "aws.prod"
  name     = "${var.domain}"
}

resource "aws_route53_record" "main" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_zone.zone_id}"
  name     = "${var.domain}"
  type     = "A"
  ttl      = "300"
  records  = ["${digitalocean_droplet.cljdoc_api.ipv4_address}"]
}

resource "aws_route53_record" "xyz_main" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_zone.zone_id}"
  name     = "${var.xyz_domain}"
  type     = "A"
  ttl      = "300"
  records  = ["${digitalocean_droplet.cljdoc_api.ipv4_address}"]
}

resource "aws_route53_record" "dokku" {
  provider = "aws.prod"
  zone_id  = "${aws_route53_zone.cljdoc_zone.zone_id}"
  name     = "clojars-stats.${var.domain}"
  type     = "A"
  ttl      = "300"
  records  = ["167.99.133.5"]
}

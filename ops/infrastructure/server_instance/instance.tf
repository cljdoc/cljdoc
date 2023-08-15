variable "xyz_zone" {}
variable "org_zone" {}
variable "image_id" {}
variable "xyz_domain" {}
variable "org_domain" {}

output "ip" {
  value = digitalocean_droplet.cljdoc_01.ipv4_address
}

provider "aws" {
  alias = "prod"
}

resource "digitalocean_droplet" "cljdoc_01" {
  image      = var.image_id
  name       = var.org_domain
  region     = "ams3"
  size       = "s-2vcpu-4gb"
  monitoring = true

  # supplying a key here seems to be the only way to
  # not get a root password via email, despite having
  # added SSH keys to the snapshot/image before
  ssh_keys = ["18144068"]
}

resource "aws_route53_record" "org_record" {
  provider = aws.prod
  zone_id  = var.org_zone
  name     = var.org_domain
  type     = "A"
  ttl      = "300"
  records  = [digitalocean_droplet.cljdoc_01.ipv4_address]
}

resource "aws_route53_record" "xyz_record" {
  provider = aws.prod
  zone_id  = var.xyz_zone
  name     = var.xyz_domain
  type     = "A"
  ttl      = "300"
  records  = [digitalocean_droplet.cljdoc_01.ipv4_address]
}

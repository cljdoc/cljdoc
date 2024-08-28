terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

variable "org_zone" {}
variable "org_domain" {}

output "ip" {
  value = exoscale_compute_instance.cljdoc_01.public_ip_address
}

data "exoscale_template" "debian" {
  zone = var.org_zone
  name = "Linux Debian 12 (Bookworm) 64-bit"
}

# TODO: Probably should create this rather than rely on existing configured default
data "exoscale_security_group" "default" {
  name = "default"
}

resource "exoscale_ssh_key" "cljdoc_ssh_key" {
  name       = "cljdoc-ssh"
  # TODO: precreated maybe not the best idea? Alternative?
  public_key = file("~/.ssh/id_ed25519_exoscale.pub")
}

resource "exoscale_compute_instance" "cljdoc_01" {
  name               = var.org_domain
  template_id        = data.exoscale_template.debian.id
  type               = "standard.medium"
  zone               = var.org_zone
  disk_size          = 50
  security_group_ids = [
    data.exoscale_security_group.default.id
  ]
  user_data          = file("cloud-config.yaml")
  ssh_key            = exoscale_ssh_key.cljdoc_ssh_key.id
  # old droplet config
  #image      = var.image_id
  #name       = var.org_domain
  #region     = "ams3"
  #size       = "s-2vcpu-4gb"
  #monitoring = true

  # supplying a key here seems to be the only way to
  # not get a root password via email, despite having
  # added SSH keys to the snapshot/image before
  #ssh_keys = ["18144068"]
}

# TODO: Update for exoscale
#resource "aws_route53_record" "org_record" {
#  provider = aws
#  zone_id  = var.org_zone
#  name     = var.org_domain
#  type     = "A"
#  ttl      = "300"
#  records  = [exoscale_compute_instance.cljdoc_01.pubic_ip_address]
#}

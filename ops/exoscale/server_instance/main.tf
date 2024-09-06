terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

variable "org_domain" {}
variable "exoscale_zone" {}

# Static IP via Elastic IP

resource "exoscale_elastic_ip" "cljdoc" {
  zone = var.exoscale_zone
}

# Packer built image based on Exoscale's debian template

data "exoscale_template" "debian" {
  zone = var.exoscale_zone # see providers.tf
  name = "debian-cljdoc"
  visibility = "private"
}

# Firewall

resource "exoscale_security_group" "cljdoc" {
  name        = "cljdoc-firewall"
  description = "Firewall rules for cljdoc"
}

resource "exoscale_security_group_rule" "ssh" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 22
  end_port          = 22
}

resource "exoscale_security_group_rule" "http" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 80
  end_port          = 80
}

resource "exoscale_security_group_rule" "https" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 443
  end_port          = 443
}

# SSH Keys

resource "exoscale_ssh_key" "cljdoc_ssh_key" {
  name       = "cljdoc-ssh"
  # TODO: precreated maybe not the best idea? Alternative?
  # TODO: yeah, I'll need to allow both martin and myself so need something different
  public_key = file("~/.ssh/id_ed25519_exoscale.pub")
}

# Server Instance

resource "exoscale_compute_instance" "cljdoc_01" {
  name               = var.org_domain
  template_id        = data.exoscale_template.debian.id
  type               = "standard.medium"
  zone               = var.exoscale_zone
  elastic_ip_ids     = [exoscale_elastic_ip.cljdoc.id]
  disk_size          = 50
  security_group_ids = [ exoscale_security_group.cljdoc.id ]
  ssh_key            = exoscale_ssh_key.cljdoc_ssh_key.id
  user_data          = <<EOF
#cloud-config
runcmd:
  - ip addr add ${exoscale_elastic_ip.cljdoc.ip_address}/32 dev lo
EOF
}

# Outputs

output "instance_ip" {
  value = exoscale_compute_instance.cljdoc_01.public_ip_address
}

output "elastic_ip" {
  value = exoscale_elastic_ip.cljdoc.ip_address
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

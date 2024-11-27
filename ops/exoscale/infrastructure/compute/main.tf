terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

variable "exoscale_zone" {}
variable "base_authorized_key" {}
variable "additional_authorized_keys" {}

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

# the compute instance only allows a single ssh key to be specified
# additional_authorized_keys are setup via cloud init
resource "exoscale_ssh_key" "cljdoc_base_ssh_key" {
  name = "cljdoc-base-ssh"
  public_key = var.base_authorized_key
}

# Server Instance

resource "exoscale_compute_instance" "cljdoc_01" {
  name               = "cljdoc.org"
  template_id        = data.exoscale_template.debian.id
  type               = "standard.medium"
  zone               = var.exoscale_zone
  elastic_ip_ids     = [exoscale_elastic_ip.cljdoc.id]
  disk_size          = 50
  security_group_ids = [ exoscale_security_group.cljdoc.id ]
  ssh_key            = exoscale_ssh_key.cljdoc_base_ssh_key.id
  user_data          = <<EOF
#cloud-config
runcmd:
  - ip addr add ${exoscale_elastic_ip.cljdoc.ip_address}/32 dev lo
write_files:
  - path: /home/debian/.ssh/authorized_keys
    owner: debian:debian
    permissions: '0600'
    append: true
    content: |
      %{ for key in var.additional_authorized_keys ~}
      ${key}
      %{ endfor ~}
EOF
}

# Outputs

output "instance_ip" {
  value = exoscale_compute_instance.cljdoc_01.public_ip_address
}

output "elastic_ip" {
  value = exoscale_elastic_ip.cljdoc.ip_address
}

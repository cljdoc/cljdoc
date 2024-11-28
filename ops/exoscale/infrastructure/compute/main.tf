terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

variable "name" {}
variable "exoscale_zone" {}
variable "template_id" {}
variable "instance_type" {}
variable "disk_size" {}
variable "base_authorized_key" {}
variable "additional_authorized_keys" {}
variable "security_group_ids" {}
variable "elastic_ip_id" {
  description = "Optional elastic IP ID to associate with instance"
  type        = string
  default     = null
}
variable "elastic_ip_address" {
  description = "Optional elastic IP address for cloud-init config"
  type        = string
  default     = null
}
variable "ssh_key_id" {}

# Server Instance

resource "exoscale_compute_instance" "cljdoc" {
  name               = var.name
  template_id        = var.template_id
  type               = var.instance_type
  zone               = var.exoscale_zone
  elastic_ip_ids     = var.elastic_ip_id != null ? [var.elastic_ip_id] : []
  disk_size          = var.disk_size
  security_group_ids = var.security_group_ids
  ssh_key            = var.ssh_key_id
  user_data          = <<EOF
#cloud-config
%{if var.elastic_ip_address != null~}
runcmd:
  - ip addr add ${var.elastic_ip_address}/32 dev lo
%{endif~}
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
  value = exoscale_compute_instance.cljdoc.public_ip_address
}


terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

variable "domain_name" {}
variable "ttl" {}
variable "ip_address" {}
variable "record_name" {}

resource "exoscale_domain" "domain" {
  name = var.domain_name
}

resource "exoscale_domain_record" "a_record" {
  domain      = exoscale_domain.domain.id
  name        = var.record_name
  record_type = "A"
  ttl         = var.ttl
  content     = var.ip_address
}


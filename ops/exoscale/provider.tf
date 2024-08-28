terraform {
  required_providers {
    exoscale = {
      source  = "exoscale/exoscale"
      version = "~> 0.60"
    }
  }
  required_version = ">= 1.9.5"
}

variable "exoscale_api_key" { type = string }
variable "exoscale_api_secret" { type = string }
provider "exoscale" {
  key    = var.exoscale_api_key
  secret = var.exoscale_api_secret
}

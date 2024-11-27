terraform {
  required_providers {
    exoscale = {
      source  = "exoscale/exoscale"
      version = "0.62.1"
    }
    # Exoscale uses aws provider for its s3 compatible Object Store
    aws = {
      source  = "hashicorp/aws"
      version = "5.78.0"
    }
  }
  required_version = ">= 1.10.0"
}

variable "exoscale_api_key" {
  type = string
  sensitive = true
}

variable "exoscale_api_secret" {
  type = string
  sensitive = true
}

variable "exoscale_zone" {
  type    = string
  default = "ch-gva-2"
}

variable "base_authorized_key" {
  description = "The base SSH public keys to be authorized"
  type        = string
}

variable "additional_authorized_keys" {
  description = "A list of additional SSH public keys to be authorized"
  type        = list(string)
}

provider "exoscale" {
  key    = var.exoscale_api_key
  secret = var.exoscale_api_secret
}

# Configure aws provider to work against Exoscale Object Store
provider "aws" {
  endpoints {
    s3 = "https://sos-${var.exoscale_zone}.exo.io"
  }
  # vars defined in providers.tf
  region     = var.exoscale_zone
  access_key = var.exoscale_api_key
  secret_key = var.exoscale_api_secret

  # Disable AWS-specific features (as per Exoscale docs)
  skip_credentials_validation = true
  skip_region_validation      = true
  skip_requesting_account_id  = true
}

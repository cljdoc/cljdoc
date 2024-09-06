terraform {
  required_providers {
    exoscale = {
      source  = "exoscale/exoscale"
      version = "~> 0.60"
    }
    # Exoscale uses aws provider for its s3 compatible Object Store
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.65"
    }
  }
  required_version = ">= 1.9.5"
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

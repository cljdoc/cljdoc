variable "aws_access_key_id" {}
variable "aws_secret_key" {}
variable "do_token" {}

variable "aws_region" {
  default = "us-east-1"
}

variable "domain" {}
variable "domainAlias" {}

variable "org_domain" {}

variable "backups_bucket_name" {}

variable "cf_alias_zone_id" {
  description = "Fixed hardcoded constant zone_id that is used for all CloudFront distributions"
  default     = "Z2FDTNDATAQYW2"
}

variable "bucket_name" {}

terraform {
  required_providers {
    exoscale = {
      source  = "exoscale/exoscale"
    }
    # Exoscale uses aws provider for its s3 compatible Object Store
    aws = {
      source = "hashicorp/aws"
    }
  }
}

resource "aws_s3_bucket" "public_bucket" {
  bucket   = var.bucket_name
}

resource "exoscale_iam_role" "read_only_role" {
  name        = "${var.bucket_name}-bucket-public-read-role"
  description = "Role for public read-only access to the ${var.bucket_name} bucket"
  editable    = true

  policy = {
    default_service_strategy = "deny"
    services = {
      sos = {
        type = "rules"
        rules = [
          {
            expression = "operation in ['list-buckets', 'get-object']"
            action     = "allow"
          },
          {
            expression = "parameters.bucket == '${var.bucket_name}'"
            action     = "allow"
          }
        ]
      }
    }
  }
}

resource "exoscale_iam_role" "read_write_role" {
  name        = "${var.bucket_name}-bucket-read-write-role"
  description = "Role for read-write access to the ${var.bucket_name} bucket"
  editable    = true

  policy = {
    default_service_strategy = "deny"
    services = {
      sos = {
        type = "rules"
        rules = [
          {
            expression = "operation in ['list-buckets', 'get-object', 'put-object', 'delete-object']"
            action     = "allow"
          },
          {
            expression = "parameters.bucket == '${var.bucket_name}'"
            action     = "allow"
          }
        ]
      }
    }
  }
}

resource "exoscale_iam_api_key" "bucket_write_user_key" {
  name    = "${var.bucket_name}-write-api-key"
  role_id = exoscale_iam_role.read_write_role.id
}

output "bucket_name" {
  value = var.bucket_name
}

output "write_key" {
  value = exoscale_iam_api_key.bucket_write_user_key.key
}

output "write_secret" {
  value = exoscale_iam_api_key.bucket_write_user_key.secret
}

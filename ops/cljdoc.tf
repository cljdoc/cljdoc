# Based on https://github.com/sjevs/terraform-static-website-s3-cloudfront (MIT)

variable "aws_access_key_id" {}
variable "aws_secret_key" {}
variable "region" { default = "us-east-1" }
variable "pgp_key" {}

variable "domain" {}
variable "domainAlias" {}

variable "cf_alias_zone_id" {
  description = "Fixed hardcoded constant zone_id that is used for all CloudFront distributions"
  default = "Z2FDTNDATAQYW2"
}

provider "aws" {
  alias = "prod"

  region = "${var.region}"
  access_key = "${var.aws_access_key_id}"
  secret_key = "${var.aws_secret_key}"
}

resource "random_pet" "server" {
  # https://www.terraform.io/docs/providers/random/r/pet.html
  keepers = {
    # Generate a new pet name each time we switch to a new domain
    domain = "${var.domain}"
  }
}

data "aws_acm_certificate" "cljdoc_certificate" {
  provider = "aws.prod"
  domain   = "${var.domain}"
  statuses = ["ISSUED"]
}

data "aws_iam_policy_document" "cljdoc_html_bucket_policy" {
  provider = "aws.prod"

  statement {
    sid    = "PublicReadForGetBucketObjects"
    effect = "Allow"

    principals = [
      {
        type        = "AWS"
        identifiers = ["*"]
      },
    ]

    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.domain}-${random_pet.server.id}/*"]
  }
}

data "aws_iam_policy_document" "cljdoc_html_bucket_write_user_policy" {
  provider = "aws.prod"

  statement {
    effect  = "Allow"
    actions = ["s3:*"]

    resources = [
      "arn:aws:s3:::${aws_s3_bucket.cljdoc_html_bucket.id}",
      "arn:aws:s3:::${aws_s3_bucket.cljdoc_html_bucket.id}/*",
    ]
  }
}

# S3 Bucket ----------------------------------------------------------

resource "aws_s3_bucket" "cljdoc_html_bucket" {
  provider = "aws.prod"

  bucket = "${var.domain}-${random_pet.server.id}"
  acl = "public-read"
  # TODO figure out if we can move this policy to a separate .json file
  # Seems this should work — we'll need to put in the bucket name though
  # policy = "${file("policy.json")}"
  policy = "${data.aws_iam_policy_document.cljdoc_html_bucket_policy.json}"

  website {
    index_document = "index.html"
    error_document = "404.html"
  }
}

# Cloudfront ---------------------------------------------------------

resource "aws_cloudfront_distribution" "cljdoc_cdn" {
  provider = "aws.prod"
  depends_on = ["aws_s3_bucket.cljdoc_html_bucket"]
  enabled = true
  default_root_object = "index.html"
  aliases = ["${var.domain}"]

  origin {
    domain_name = "${aws_s3_bucket.cljdoc_html_bucket.website_endpoint}"
    origin_id = "cljdoc_html_bucket_origin"

    custom_origin_config {
      http_port = "80"
      # website endpoints do not support https
      origin_protocol_policy = "http-only"
      # this is required but should never be relevant
      https_port = "443"
      origin_ssl_protocols = [ "SSLv3", "TLSv1", "TLSv1.1", "TLSv1.2" ]
    }
  }

  default_cache_behavior {
    allowed_methods = [ "GET", "HEAD", "OPTIONS" ]
    cached_methods = [ "GET", "HEAD" ]
    target_origin_id = "cljdoc_html_bucket_origin"
    viewer_protocol_policy = "redirect-to-https"
    compress = true
    default_ttl = 86400    # 1 day
    min_ttl = 0
    max_ttl = 31536000     # 365 days

    forwarded_values {
      query_string = false
      cookies {
        forward = "none"
      }
    }
  }

  viewer_certificate {
    acm_certificate_arn = "${data.aws_acm_certificate.cljdoc_certificate.arn}"
    ssl_support_method = "sni-only"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }
}

# Route53 ------------------------------------------------------------

resource "aws_route53_zone" "cljdoc_zone" {
  provider = "aws.prod"
  name = "${var.domain}"
}

resource "aws_route53_record" "cdn_record" {
  provider = "aws.prod"
  zone_id = "${aws_route53_zone.cljdoc_zone.zone_id}"
  name = "${var.domain}"
  type = "A"

  alias {
    name = "${aws_cloudfront_distribution.cljdoc_cdn.domain_name}"
    zone_id = "${var.cf_alias_zone_id}"
    evaluate_target_health = false # not supported for Cloudfront distributions
  }
}

# Access Keys --------------------------------------------------------

resource "aws_iam_user" "cljdoc_html_bucket_write_user" {
  provider = "aws.prod"
  # TODO use bucket name in user name
  # TODO turn bucket name into local var
  name = "my-bucket-user"
  path = "/cljdoc/"
}

resource "aws_iam_access_key" "cljdoc_html_bucket_write_user_key" {
  provider = "aws.prod"
  user = "${aws_iam_user.cljdoc_html_bucket_write_user.name}"
  pgp_key = "${var.pgp_key}"
}

resource "aws_iam_user_policy" "cljdoc_html_bucket_write_user_policy" {
  provider = "aws.prod"
  name     = "cljdoc_bucket_upload"
  user     = "${aws_iam_user.cljdoc_html_bucket_write_user.name}"

  policy = "${data.aws_iam_policy_document.cljdoc_html_bucket_write_user_policy.json}"
}

# Outputs ------------------------------------------------------------

output "bucket_name" {
  value = "${aws_s3_bucket.cljdoc_html_bucket.id}"
}
output "bucket_user_access_key" {
  value = "${aws_iam_access_key.cljdoc_html_bucket_write_user_key.id}"
}
output "bucket_user_secret_key" {
  value = "${aws_iam_access_key.cljdoc_html_bucket_write_user_key.encrypted_secret}"
}
output "cloudfront_id" {
  value = "${aws_cloudfront_distribution.cljdoc_cdn.id}"
}
output "cloudfront_url" {
  value = "${aws_cloudfront_distribution.cljdoc_cdn.url}"
}
output "hosted_zone_name_servers" {
  value = "${aws_route53_zone.cljdoc_zone.name_servers}"
}
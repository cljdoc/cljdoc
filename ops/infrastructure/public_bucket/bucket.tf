variable "bucket_name" {}

data "aws_iam_policy_document" "public_read_bucket_policy" {
  statement {
    sid    = "PublicReadForGetBucketObjects"
    effect = "Allow"

    principals {
        type        = "AWS"
        identifiers = ["*"]
      }

    actions   = ["s3:GetObject"]
    resources = ["arn:aws:s3:::${var.bucket_name}/*"]
  }
}

resource "aws_s3_bucket" "public_bucket" {
  bucket   = var.bucket_name
}

resource "aws_s3_bucket_policy" "public_read_policy" {
  bucket   = aws_s3_bucket.public_bucket.id
  policy   = data.aws_iam_policy_document.public_read_bucket_policy.json
}

data "aws_iam_policy_document" "bucket_write_user_policy" {
  statement {
    effect  = "Allow"
    actions = ["s3:*"]

    resources = [
      aws_s3_bucket.public_bucket.arn,
      "${aws_s3_bucket.public_bucket.arn}/*"
    ]
  }
}

resource "aws_iam_user_policy" "bucket_write_user_policy" {
  name     = "write_bucket_policy"
  user     = aws_iam_user.bucket_write_user.name

  policy = data.aws_iam_policy_document.bucket_write_user_policy.json
}

resource "aws_iam_user" "bucket_write_user" {
  name     = "${var.bucket_name}-user"
  path     = "/cljdoc/"
}

resource "aws_iam_access_key" "bucket_write_user_key" {
  user     = aws_iam_user.bucket_write_user.name
}

output "bucket_name" {
  value = var.bucket_name
}

output "write_user_access_key" {
  value = aws_iam_access_key.bucket_write_user_key.id
}

output "write_user_secret" {
  value = aws_iam_access_key.bucket_write_user_key.secret
}

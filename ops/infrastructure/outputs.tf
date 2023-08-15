# Outputs ------------------------------------------------------------

# Releases Bucket
output "releases_bucket_name" {
  value = module.releases_bucket.bucket_name
}

output "releases_bucket_user_access_key" {
  value = module.releases_bucket.write_user_access_key
}

output "releases_bucket_user_secret_key" {
  value = module.releases_bucket.write_user_secret
  sensitive = true
}

# Backups Bucket
output "backups_bucket_name" {
  value = module.backups_bucket.bucket_name
}

output "backups_bucket_user_access_key" {
  value = module.backups_bucket.write_user_access_key
}

output "backups_bucket_user_secret_key" {
  value = module.backups_bucket.write_user_secret
  sensitive = true
}

# Hosted Zone / IP
output "xyz_hosted_zone_name_servers" {
  value = aws_route53_zone.cljdoc_xyz_zone.name_servers
}

output "org_hosted_zone_name_servers" {
  value = aws_route53_zone.cljdoc_org_zone.name_servers
}

output "main_ip" {
  value = module.main_server.ip
}


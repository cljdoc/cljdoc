# Exoscale Object Store

module "releases_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-releases"
}

module "backups_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-backups"
}

# Exoscale Compute

module "main_server" {
  source     = "./server_instance"
  org_domain = "cljdoc.org"
  exoscale_zone = var.exoscale_zone
}

# Exoscale DNS

# Outputs
output "cljdoc_releases_bucket" {
  value = module.releases_bucket.bucket_name
}

output "cljdoc_releases_bucket_key" {
  sensitive = true
  value = module.releases_bucket.write_key
}

output "cljdoc_releases_bucket_secret" {
  sensitive = true
  value = module.releases_bucket.write_secret
}

output "cljdoc_backups_bucket" {
  value = module.backups_bucket.bucket_name
}

output "cljdoc_backups_bucket_key" {
  sensitive = true
  value = module.backups_bucket.write_key
}

output "cljdoc_backups_bucket_secret" {
  sensitive = true
  value = module.backups_bucket.write_secret
}

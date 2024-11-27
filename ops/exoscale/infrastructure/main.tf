# Exoscale Object Store

module "backups_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-backups"
}

# Exoscale Compute

module "main_server" {
  source     = "./compute"
  exoscale_zone = var.exoscale_zone
  base_authorized_key = var.base_authorized_key
  additional_authorized_keys = var.additional_authorized_keys
}

# Outputs
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

output "cljdoc_instance_ip" {
  value = module.main_server.instance_ip
}

output "cljdoc_static_ip" {
  value = module.main_server.elastic_ip
}

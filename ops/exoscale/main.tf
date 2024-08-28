# Exoscale Object Store

# TODO: Work out what we need to do for exoscale
#module "backup_bucket" {
#  source      = "./public_bucket"
#  bucket_name = var.backups_bucket_name
#}

# Exoscale Compute

module "main_server" {
  source     = "./server_instance"
  org_zone   = "ch-gva-2"
  org_domain = "cljdoc.org"
}

# Exoscale DNS

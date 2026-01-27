module "firewall" {
  source = "./firewall"
}

module "backups_bucket" {
  source      = "./public_bucket"
  bucket_name = "cljdoc-backups"
}

# Static IP via Elastic IP
resource "exoscale_elastic_ip" "cljdoc_prod" {
  zone = var.exoscale_zone
}

# TODO: delete afer migrating to ssh_keys
# the compute instance only allows a single ssh key to be specified
# additional_authorized_keys are setup via cloud init
# see compute/main.tf
resource "exoscale_ssh_key" "cljdoc_base_ssh_key" {
  name = "cljdoc-base-ssh"
  public_key = var.base_authorized_key
}

resource "exoscale_ssh_key" "cljdoc_ssh_keys" {
  for_each   = var.authorized_keys
  name       = "${each.key}"
  public_key = each.value
}

# Packer built image based on Exoscale's debian template
data "exoscale_template" "debian" {
  zone = var.exoscale_zone # see providers.tf
  name = "debian-cljdoc"
  visibility = "private"
}

data "exoscale_template" "debian-03" {
  zone = var.exoscale_zone # see providers.tf
  name = "debian-cljdoc-20260126-0727"
  visibility = "private"
}

module "dns" {
  source      = "./dns"
  for_each    = toset(["cljdoc.org"])
  domain_name = each.key
  record_name = ""
  ttl         = 300
  ip_address  = exoscale_elastic_ip.cljdoc_prod.ip_address
}

# TODO: delete after migrating to cljdoc_03
module "cljdoc_02" {
  name = "cljdoc.org"
  source = "./compute-legacy"
  template_id = data.exoscale_template.debian.id
  instance_type = "standard.large"
  disk_size = 50
  exoscale_zone = var.exoscale_zone
  security_group_ids = [module.firewall.security_group_id]
  base_authorized_key = var.base_authorized_key
  additional_authorized_keys = var.additional_authorized_keys
  elastic_ip_id = exoscale_elastic_ip.cljdoc_prod.id
  elastic_ip_address = exoscale_elastic_ip.cljdoc_prod.ip_address
  ssh_key_id = exoscale_ssh_key.cljdoc_base_ssh_key.id
}

module "cljdoc_03" {
  name = "cljdoc.org"
  source = "./compute"
  template_id = data.exoscale_template.debian-03.id
  instance_type = "standard.large"
  disk_size = 50
  exoscale_zone = var.exoscale_zone
  security_group_ids = [module.firewall.security_group_id]
  # testing for now, do go live yet!
  # elastic_ip_id = exoscale_elastic_ip.cljdoc_prod.id
  # elastic_ip_address = exoscale_elastic_ip.cljdoc_prod.ip_address
  ssh_key_ids = [for k in exoscale_ssh_key.cljdoc_ssh_keys : k.id]
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

output "cljdoc_02_instance_ip" {
  value = module.cljdoc_02.instance_ip
}

output "cljdoc_03_instance_ip" {
  value = module.cljdoc_03.instance_ip
}

output "cljdoc_static_ip" {
  value = exoscale_elastic_ip.cljdoc_prod.ip_address
}

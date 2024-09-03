packer {
  required_plugins {
    exoscale = {
      version = ">= 0.5.0"
      source  = "github.com/exoscale/exoscale"
    }
  }
}

variable "exoscale_api_key" { type = string }
variable "exoscale_api_secret" { type = string }
variable "nomad_version" { default = "1.8.3" }
variable "consul_version" { default = "1.19.2" }

source "exoscale" "debian-cljdoc" {
  api_key                  = var.exoscale_api_key
  api_secret               = var.exoscale_api_secret
  instance_template        = "Linux Debian 12 (Bookworm) 64-bit"
  template_zones           = ["ch-gva-2"]
  template-name            = "debian-cljdoc-${formatdate("YYYY-MM-DD-hh:mm", timestamp())}"
  template_username        = "debian"
  ssh_username             = "debian"
}

build {
  sources = ["source.exoscale.debian-cljdoc"]

  provisioner "shell" {
    inline = [
      # Create temporary directory for downloads
      "rm -rf /tmp/dl",
      "mkdir -p /tmp/dl/nomad /tmp/dl/consul",

      # Update and install prerequisites
      "sudo DEBIAN_FRONTEND=noninteractive apt-get update",
      "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y wget curl gnupg lsb-release unzip",

      # Add Docker's official GPG key
      "curl -fsSL https://download.docker.com/linux/debian/gpg | sudo gpg --dearmor -o /usr/share/keyrings/docker-archive-keyring.gpg",
      # Set up the stable Docker repository
      "echo \"deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/docker-archive-keyring.gpg] https://download.docker.com/linux/debian $(lsb_release -cs) stable\" | sudo tee /etc/apt/sources.list.d/docker.list > /dev/null",
      # Update the apt package index again and install Docker
      "sudo DEBIAN_FRONTEND=noninteractive apt-get update",
      "sudo DEBIAN_FRONTEND=noninteractive apt-get install -y docker-ce",
      # Enable Docker
      "sudo systemctl enable docker",

      # Download nomad
      "wget --no-verbose -P /tmp/dl https://releases.hashicorp.com/nomad/${var.nomad_version}/nomad_${var.nomad_version}_linux_amd64.zip",
      "wget --no-verbose -P /tmp/dl https://releases.hashicorp.com/nomad/${var.nomad_version}/nomad_${var.nomad_version}_SHA256SUMS",
      # Verify nomad
      "cd /tmp/dl && grep nomad_${var.nomad_version}_linux_amd64.zip nomad_${var.nomad_version}_SHA256SUMS | sha256sum -c -",
      # Install nomad
      "unzip -q -j /tmp/dl/nomad_${var.nomad_version}_linux_amd64.zip -d /tmp/dl/nomad",
      "sudo mv /tmp/dl/nomad/nomad /usr/local/bin/",

      # Download consul
      "wget --no-verbose -P /tmp/dl https://releases.hashicorp.com/consul/${var.consul_version}/consul_${var.consul_version}_linux_amd64.zip",
      "wget --no-verbose -P /tmp/dl https://releases.hashicorp.com/consul/${var.consul_version}/consul_${var.consul_version}_SHA256SUMS",
      # Verify consul
      "cd /tmp/dl && grep consul_${var.consul_version}_linux_amd64.zip consul_${var.consul_version}_SHA256SUMS | sha256sum -c -",
      # Install consul
      "unzip -q -j /tmp/dl/consul_${var.consul_version}_linux_amd64.zip -d /tmp/dl/consul",
      "sudo mv /tmp/dl/consul/consul /usr/local/bin/",

      # Clean up downloaded files
      "rm -rf /tmp/dl"
    ]
  }

  provisioner "file" {
    source      = "conf"
    destination = "/tmp"
  }

  provisioner "shell" {
    inline = [
      # install config files
      "sudo install -Dm644 /tmp/conf/nomad.server.hcl /etc/nomad.d/server.hcl",
      "sudo install -Dm644 /tmp/conf/nomad.service /etc/systemd/system/nomad.service",
      "sudo install -Dm644 /tmp/conf/consul.service /etc/systemd/system/consul.service",
      "rm -rf /tmp/conf",

      # setup for nomad (nomad docs say it should be run as root)
      "sudo mkdir -p /var/lib/nomad",
      "sudo mkdir -p /var/lib/alloc_mounts",

      # setup for consul
      "sudo useradd --system --home /etc/consul.d --shell /bin/false consul",
      "sudo mkdir -p /etc/consul.d",
      "sudo mkdir -p /var/lib/consul",
      "sudo chown -R consul:consul /etc/consul.d",
      "sudo chown -R consul:consul /var/lib/consul",

      # Reload systemd to pick up new service files
      "sudo systemctl daemon-reload",
      # Enable consul and nomad
      "sudo systemctl enable consul",
      "sudo systemctl enable nomad"
    ]
  }
}

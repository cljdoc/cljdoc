terraform {
  required_providers {
    exoscale = {
      source = "exoscale/exoscale"
    }
  }
}

resource "exoscale_security_group" "cljdoc" {
  name        = "cljdoc-firewall"
  description = "Firewall rules for cljdoc"
}

resource "exoscale_security_group_rule" "ssh" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 22
  end_port          = 22
}

resource "exoscale_security_group_rule" "http" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 80
  end_port          = 80
}

resource "exoscale_security_group_rule" "https" {
  security_group_id = exoscale_security_group.cljdoc.id
  type              = "INGRESS"
  protocol          = "TCP"
  cidr              = "0.0.0.0/0"
  start_port        = 443
  end_port          = 443
}

output "security_group_id" {
  value = exoscale_security_group.cljdoc.id
}

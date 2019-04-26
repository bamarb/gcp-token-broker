# Copyright 2019 Google LLC
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# http://www.apache.org/licenses/LICENSE-2.0
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.


// VPC ------------------------------------------------------------------

resource "google_compute_network" "origin" {
  name                    = "origin-network"
  auto_create_subnetworks = "false"
  depends_on = ["google_project_service.service_compute"]
}

resource "google_compute_subnetwork" "origin_subnet" {
  name          = "origin-subnet"
  ip_cidr_range = "${var.origin_subnet_cidr}"
  region        = "${var.gcp_region}"
  network       = "${google_compute_network.origin.self_link}"
  private_ip_google_access = true
}

resource "google_compute_firewall" "origin_allow_external_kdcs" {
  name    = "origin-allow-external-kdcs"
  network = "${google_compute_network.origin.name}"

  allow {
    protocol = "icmp"
  }
  allow {
    protocol = "tcp"
    ports    = ["88"]
  }
  allow {
    protocol = "udp"
    ports    = ["88"]
  }
  source_ranges = [
    "${var.client_subnet_cidr}",
    "${var.broker_subnet_cidr}"
  ]
}

resource "google_compute_firewall" "origin_allow_ssh" {
  name    = "origin-allow-ssh"
  network = "${google_compute_network.origin.name}"

  allow {
    protocol = "icmp"
  }
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
  source_ranges = [
    "35.235.240.0/20" // For IAP tunnel (see: https://cloud.google.com/iap/docs/using-tcp-forwarding)
  ]
}

resource "google_compute_firewall" "origin_allow_internal" {
  name    = "origin-allow-internal"
  network = "${google_compute_network.origin.name}"

  allow {
    protocol = "icmp"
  }
  allow {
    protocol = "tcp"
    ports    = ["1-65535"]
  }
  allow {
    protocol = "udp"
    ports    = ["1-65535"]
  }
  source_ranges = [
    "${var.origin_subnet_cidr}"
  ]
}


// KDC ------------------------------------------------------------------

data "template_file" "startup_script_origin_kdc" {
  template = "${file("startup-script-kdc.tpl")}"

  vars {
    realm = "${var.origin_realm}"
    project = "${var.gcp_project}"
    zone = "${var.gcp_zone}"
    extra_commands = <<EOT
        # Create user principals
        kadmin.local -q "addprinc -pw ${var.test_users[0]} ${var.test_users[0]}"
        kadmin.local -q "addprinc -pw ${var.test_users[1]} ${var.test_users[1]}"
        kadmin.local -q "addprinc -pw ${var.test_users[2]} ${var.test_users[2]}"

        # Create broker principal and keytab
        kadmin.local -q "addprinc broker/${var.broker_service_hostname}"
        kadmin.local -q "ktadd -k /etc/broker.keytab broker/${var.broker_service_hostname}"
    EOT
  }
}

resource "google_compute_instance" "origin_kdc" {
    name = "origin-kdc"
    machine_type = "n1-standard-1"
    boot_disk {
      initialize_params {
        image = "debian-cloud/debian-9"
      }
    }
    network_interface {
      subnetwork = "${google_compute_subnetwork.origin_subnet.self_link}"
      network_ip = "${var.origin_kdc_ip}"
    }
    metadata {
      startup-script = "${data.template_file.startup_script_origin_kdc.rendered}"
    }
    service_account {
      scopes = ["cloud-platform"]
    }
    depends_on = ["google_project_service.service_compute"]
}


// NAT gateway ----------------------------------------

resource "google_compute_router" "origin" {
  name    = "origin"
  network = "${google_compute_network.origin.self_link}"
}

resource "google_compute_router_nat" "origin_nat" {
  name                               = "origin"
  router                             = "${google_compute_router.origin.name}"
  nat_ip_allocate_option             = "AUTO_ONLY"
  source_subnetwork_ip_ranges_to_nat = "ALL_SUBNETWORKS_ALL_IP_RANGES"
}
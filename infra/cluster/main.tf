# The on-demand stack (Phase 5 spec, section 1.2): everything that bills by the hour. `make aws-up`
# applies it and `make aws-down` destroys it; about $0.45 an hour while it exists.
terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    cloudflare = {
      source  = "cloudflare/cloudflare"
      version = "~> 5.0"
    }
  }

  backend "s3" {
    key          = "cluster/terraform.tfstate"
    region       = "us-east-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = { project = "sports-notifier" }
  }
}

provider "cloudflare" {}

data "aws_caller_identity" "me" {}

# The kept stack's outputs: the certificate, the Cloudflare zone, the dump bucket.
data "terraform_remote_state" "base" {
  backend = "s3"
  config = {
    bucket = "sports-notifier-tfstate-${data.aws_caller_identity.me.account_id}"
    key    = "base/terraform.tfstate"
    region = "us-east-2"
  }
}

data "aws_availability_zones" "available" {
  state = "available"
}

locals {
  name = "sports-notifier"
  base = data.terraform_remote_state.base.outputs
  # Three zones: a node, a Kafka broker and a subnet in each.
  azs = slice(data.aws_availability_zones.available.names, 0, 3)
  # Traefik's fixed node ports (deploy/aws/traefik-values.yaml); the NLB forwards to these.
  web_node_port       = 30080
  websecure_node_port = 30443
}

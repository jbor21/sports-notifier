# The kept stack: what survives `make aws-down` (Phase 5 spec, section 1.1). Under a dollar a month
# while the cluster is down. Applied by hand, once, and again only when something here changes.
terraform {
  required_version = ">= 1.10" # S3 state locking without DynamoDB (use_lockfile)

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

  # The bucket comes from `-backend-config` (aws-bootstrap.sh made it; its name holds the account id,
  # and a backend block can't use variables). Credentials come from AWS_PROFILE=sports.
  backend "s3" {
    key          = "base/terraform.tfstate"
    region       = "us-east-2"
    encrypt      = true
    use_lockfile = true
  }
}

provider "aws" {
  region = var.region
  # Every resource carries this tag: the leftover sweep and Cost Explorer find the project by it.
  default_tags {
    tags = { project = "sports-notifier" }
  }
}

# Reads CLOUDFLARE_API_TOKEN (scripts/aws-lib.sh, cf_token): DNS edit on the one zone, nothing else.
provider "cloudflare" {}

data "aws_caller_identity" "me" {}

data "cloudflare_zone" "this" {
  filter = { name = var.domain }
}

locals {
  account_id = data.aws_caller_identity.me.account_id
  fqdn       = "${var.subdomain}.${var.domain}"
}

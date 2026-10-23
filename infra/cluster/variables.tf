# Set in infra/settings.auto.tfvars, shared by both stacks (each stack declares every setting).
variable "region" {
  type = string
}

variable "domain" {
  description = "The Cloudflare zone, e.g. example.com"
  type        = string
}

variable "subdomain" {
  description = "The app lives at <subdomain>.<domain>"
  type        = string
}

variable "budget_email" {
  type = string
}

variable "budget_usd" {
  type = number
}

# The one address allowed to reach the Kubernetes API: the Mac running aws-up, as <ip>/32. Passed in on
# every apply, because a home address changes.
variable "api_cidr" {
  type = string
}

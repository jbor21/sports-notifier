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
  description = "Where budget alerts go. Set in infra/base/private.auto.tfvars (git-ignored), not the shared file"
  type        = string
}

variable "budget_usd" {
  type = number
}

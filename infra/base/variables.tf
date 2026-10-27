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

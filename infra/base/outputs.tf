# Read by infra/cluster (terraform_remote_state) and by the make aws-* scripts.
output "ecr_url" {
  value = aws_ecr_repository.app.repository_url
}

output "dump_bucket" {
  value = aws_s3_bucket.dumps.bucket
}

# From the validation, not the certificate: anything using it waits until it is issued.
output "certificate_arn" {
  value = aws_acm_certificate_validation.app.certificate_arn
}

output "github_role_arn" {
  value = aws_iam_role.github.arn
}

output "zone_id" {
  value = data.cloudflare_zone.this.id
}

output "fqdn" {
  value = local.fqdn
}

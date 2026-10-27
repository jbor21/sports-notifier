# The TLS certificate the load balancer serves for sports.<domain>. It lives in the kept stack, so a
# teardown never re-issues it. ACM proves we own the name by a DNS record, which Terraform writes into
# Cloudflare; issuance takes a few minutes and renewal is automatic while that record stays.
resource "aws_acm_certificate" "app" {
  domain_name       = local.fqdn
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "cloudflare_dns_record" "acm_validation" {
  for_each = {
    for o in aws_acm_certificate.app.domain_validation_options : o.domain_name => o
  }
  zone_id = data.cloudflare_zone.this.id
  name    = trimsuffix(each.value.resource_record_name, ".")
  type    = each.value.resource_record_type
  content = trimsuffix(each.value.resource_record_value, ".")
  ttl     = 60
  proxied = false # ACM must see the record itself, not Cloudflare's proxy
  comment = "ACM validation for ${local.fqdn} (sports-notifier infra/base)"
}

# Waits until ACM says Issued; outputs.tf exports the ARN from here, so nothing uses an unissued cert.
resource "aws_acm_certificate_validation" "app" {
  certificate_arn         = aws_acm_certificate.app.arn
  validation_record_fqdns = [for o in aws_acm_certificate.app.domain_validation_options : o.resource_record_name]
}

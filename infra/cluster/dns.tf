# sports.<domain> → this apply's load balancer. DNS only (not proxied): Cloudflare never sees the
# traffic, and the browser gets ACM's certificate. A short TTL, because every aws-up makes a new
# load balancer with a new name.
resource "cloudflare_dns_record" "app" {
  zone_id = local.base.zone_id
  name    = local.base.fqdn
  type    = "CNAME"
  content = aws_lb.this.dns_name
  ttl     = 60
  proxied = false
  comment = "sports-notifier load balancer (infra/cluster; removed by make aws-down)"
}

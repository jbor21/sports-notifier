# The front door (spec, section 1.2). Terraform makes the load balancer itself, not a controller in
# the cluster, so `destroy` removes it. It ends TLS with the base stack's certificate and passes the
# client's address to Traefik with proxy protocol, so login throttling sees real addresses.
resource "aws_security_group" "nlb" {
  name        = "${local.name}-nlb"
  description = "Internet to the load balancer: HTTP (redirected) and HTTPS"
  vpc_id      = module.vpc.vpc_id
}

resource "aws_vpc_security_group_ingress_rule" "nlb_in" {
  for_each          = toset(["80", "443"])
  security_group_id = aws_security_group.nlb.id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = tonumber(each.key)
  to_port           = tonumber(each.key)
  ip_protocol       = "tcp"
}

resource "aws_vpc_security_group_egress_rule" "nlb_to_nodes" {
  for_each                     = toset([tostring(local.web_node_port), tostring(local.websecure_node_port)])
  security_group_id            = aws_security_group.nlb.id
  referenced_security_group_id = module.eks.node_security_group_id
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
  ip_protocol                  = "tcp"
}

# The nodes' only door from outside: Traefik's two node ports, from the load balancer alone.
resource "aws_vpc_security_group_ingress_rule" "nodes_from_nlb" {
  for_each                     = toset([tostring(local.web_node_port), tostring(local.websecure_node_port)])
  security_group_id            = module.eks.node_security_group_id
  referenced_security_group_id = aws_security_group.nlb.id
  from_port                    = tonumber(each.key)
  to_port                      = tonumber(each.key)
  ip_protocol                  = "tcp"
}

resource "aws_lb" "this" {
  name               = local.name
  load_balancer_type = "network"
  subnets            = module.vpc.public_subnets
  security_groups    = [aws_security_group.nlb.id]
}

locals {
  target_ports = {
    web       = local.web_node_port
    websecure = local.websecure_node_port
  }
}

resource "aws_lb_target_group" "traefik" {
  for_each    = local.target_ports
  name        = "${local.name}-${each.key}"
  port        = each.value
  protocol    = "TCP"
  target_type = "instance"
  vpc_id      = module.vpc.vpc_id
  # The client's address rides in the proxy protocol header; with preservation off, the nodes only
  # ever see the load balancer's own addresses, which is what the security group above allows.
  proxy_protocol_v2  = true
  preserve_client_ip = false

  health_check {
    protocol = "TCP"
  }
}

# Every node in the group is a target, including a node that replaces a dead one.
resource "aws_autoscaling_attachment" "traefik" {
  for_each               = local.target_ports
  autoscaling_group_name = module.eks.eks_managed_node_groups_autoscaling_group_names[0]
  lb_target_group_arn    = aws_lb_target_group.traefik[each.key].arn
}

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.this.arn
  port              = 80
  protocol          = "TCP"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.traefik["web"].arn
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "TLS"
  certificate_arn   = local.base.certificate_arn
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.traefik["websecure"].arn
  }
}

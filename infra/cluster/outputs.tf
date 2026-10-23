output "cluster_name" {
  value = module.eks.cluster_name
}

output "nlb_dns_name" {
  value = aws_lb.this.dns_name
}

output "vpc_cidr" {
  value = module.vpc.vpc_cidr_block
}

output "node_security_group_id" {
  value = module.eks.node_security_group_id
}

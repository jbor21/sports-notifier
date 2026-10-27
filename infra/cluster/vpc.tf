# The network. Public subnets only and no NAT gateway (DESIGN.md decision 9): a NAT gateway bills
# every hour it exists. Nodes get public addresses so they can reach ESPN and ECR; their security
# group lets nothing in from the internet except through the load balancer (nlb.tf).
module "vpc" {
  source  = "terraform-aws-modules/vpc/aws"
  version = "6.7.3"

  name           = local.name
  cidr           = "10.40.0.0/16"
  azs            = local.azs
  public_subnets = [for i in range(3) : cidrsubnet("10.40.0.0/16", 4, i)] # three /20s

  enable_nat_gateway      = false
  map_public_ip_on_launch = true

  public_subnet_tags = {
    "kubernetes.io/role/elb" = "1"
  }
}

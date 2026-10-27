# The Kubernetes cluster: AWS runs the control plane ($0.10 an hour), we rent three machines.
module "eks" {
  source  = "terraform-aws-modules/eks/aws"
  version = "21.29.0"

  name               = local.name
  kubernetes_version = "1.35" # 1.34+ for the gateway's native preStop sleep; matches k3d

  # The API answers on the internet only for the Mac that ran aws-up, and IAM checks every call.
  endpoint_public_access       = true
  endpoint_public_access_cidrs = [var.api_cidr]
  endpoint_private_access      = true

  # Access entries: whoever runs `apply` (your SSO role) is cluster admin. That is also what lets
  # the EKS console show the cluster's pods.
  authentication_mode                      = "API"
  enable_cluster_creator_admin_permissions = true

  vpc_id     = module.vpc.vpc_id
  subnet_ids = module.vpc.public_subnets

  # Off on purpose: a customer-managed KMS key costs $1 a month and lingers for weeks after each
  # destroy, and control-plane logs bill per GB. EKS already encrypts its data with an AWS-owned key.
  create_kms_key              = false
  encryption_config           = null
  create_cloudwatch_log_group = false
  enabled_log_types           = []

  addons = {
    # The pod network. enableNetworkPolicy turns on the policy agent: without it EKS silently
    # ignores every NetworkPolicy in the chart (open problem 2, closed here).
    vpc-cni = {
      before_compute       = true
      configuration_values = jsonencode({ enableNetworkPolicy = "true" })
    }
    # Hands pods AWS credentials by service account (the EBS driver here, the Postgres restore later).
    eks-pod-identity-agent = {
      before_compute = true
    }
    coredns    = {}
    kube-proxy = {}
    # Turns a PersistentVolumeClaim into an EBS disk (Kafka, Postgres, Redis).
    aws-ebs-csi-driver = {
      pod_identity_association = [{
        role_arn        = module.ebs_csi_pod_identity.iam_role_arn
        service_account = "ebs-csi-controller-sa"
      }]
    }
  }

  eks_managed_node_groups = {
    default = {
      ami_type       = "AL2023_x86_64_STANDARD"
      instance_types = ["t3.large"] # 2 vCPU, 8 GiB; on demand, not spot
      min_size       = 3
      max_size       = 3
      desired_size   = 3 # one per zone: the Auto Scaling group spreads them over the three subnets
      subnet_ids     = module.vpc.public_subnets
    }
  }
}

module "ebs_csi_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "2.10.0"

  name                      = "${local.name}-ebs-csi"
  attach_aws_ebs_csi_policy = true
}

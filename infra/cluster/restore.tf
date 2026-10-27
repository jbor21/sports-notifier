# Postgres's restore on first boot (Phase 5 spec, section 5.2) reads the last dump through Pod
# Identity: the init container in the postgres pod (service account `postgres`) gets this role, and
# nothing else in the cluster does.
module "restore_pod_identity" {
  source  = "terraform-aws-modules/eks-pod-identity/aws"
  version = "2.10.0"

  name                 = "${local.name}-restore"
  attach_custom_policy = true
  policy_statements = [
    {
      sid       = "ReadDumps"
      actions   = ["s3:GetObject"]
      resources = ["arn:aws:s3:::${local.base.dump_bucket}/dumps/*"]
    },
    {
      # Without it a missing dump answers 403, not 404, and the first boot ever couldn't tell
      # "no dump yet" from "not allowed".
      sid       = "TellMissingFromForbidden"
      actions   = ["s3:ListBucket"]
      resources = ["arn:aws:s3:::${local.base.dump_bucket}"]
    },
  ]

  associations = {
    postgres = {
      cluster_name    = module.eks.cluster_name
      namespace       = "default"
      service_account = "postgres"
    }
  }
}

# Shared by the make aws-* scripts; source it, don't run it. Every AWS call goes through the SSO
# profile "sports" (docs/aws-setup.md), so there are no long-lived keys on the Mac, and every kubectl
# call names the EKS context, so the k3d cluster is never touched by mistake.

export AWS_PROFILE=${AWS_PROFILE:-sports}
export AWS_REGION=${AWS_REGION:-us-east-2}
AWS_CONTEXT=sports-notifier-aws

aws_require_session() {
  if ! aws sts get-caller-identity >/dev/null 2>&1; then
    echo "No valid AWS session for profile $AWS_PROFILE. run: aws sso login --profile $AWS_PROFILE" >&2
    return 1
  fi
}

# Terraform's Cloudflare provider reads CLOUDFLARE_API_TOKEN.
cf_token() {
  local t
  t=$(security find-generic-password -s sports-notifier-cloudflare -w 2>/dev/null) || t=""
  if [[ -z "$t" ]]; then
    echo "No Cloudflare token in the keychain. Add it with:" >&2
    echo "  security add-generic-password -a \"\$USER\" -s sports-notifier-cloudflare -w" >&2
    return 1
  fi
  export CLOUDFLARE_API_TOKEN=$t
}

account_id() { aws sts get-caller-identity --query Account --output text; }

# Terraform's state bucket, made once by aws-bootstrap.sh. Bucket names are global across AWS, hence
# the account id.
state_bucket() { echo "sports-notifier-tfstate-$(account_id)"; }

kc() { kubectl --context "$AWS_CONTEXT" "$@"; }

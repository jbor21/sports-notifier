#!/usr/bin/env bash
# Makes the S3 bucket Terraform keeps its state in. Terraform can't store its state in a bucket it
# hasn't created yet, so this one bucket is made with the AWS CLI, once. Safe to re-run: each setting
# is applied again, and an existing bucket is left as it is.
set -euo pipefail
source "$(dirname "$0")/aws-lib.sh"

aws_require_session
bucket=$(state_bucket)

if aws s3api head-bucket --bucket "$bucket" >/dev/null 2>&1; then
  echo "State bucket $bucket exists"
else
  aws s3api create-bucket --bucket "$bucket" --create-bucket-configuration "LocationConstraint=$AWS_REGION" >/dev/null
  echo "Created state bucket $bucket"
fi

# Versioning keeps every earlier state, so a bad apply can be rolled back by hand.
aws s3api put-bucket-versioning --bucket "$bucket" --versioning-configuration Status=Enabled
aws s3api put-public-access-block --bucket "$bucket" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$bucket" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-bucket-tagging --bucket "$bucket" --tagging 'TagSet=[{Key=project,Value=sports-notifier}]'

echo "$bucket: versioning on, public access blocked, encrypted"

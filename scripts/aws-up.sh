#!/usr/bin/env bash
# make aws-up [TAG=<image tag>]: the cluster from nothing, or an update of a running one (spec,
# section 3). Terraform shows its plan and waits for "yes"; every other step is safe to repeat.
# Checks come first, so nothing starts billing for a deploy that cannot work.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/aws-lib.sh

aws_require_session
cf_token

# The image: main's latest by default (CI pushes <7-char sha> for main, pr<N> for a pull request).
TAG=${TAG:-$(git rev-parse --short=7 origin/main)}
if ! aws ecr describe-images --repository-name sports-notifier --image-ids "imageTag=$TAG" >/dev/null 2>&1; then
  echo "Image tag $TAG is not in ECR. CI pushes one per green run (main: <sha>, a PR: pr<N>); pass TAG=<tag>." >&2
  exit 1
fi
echo "== image sports-notifier:$TAG is in ECR"

# The Kubernetes API answers only this Mac's current address.
ip=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
echo "== terraform apply in infra/cluster (API open to $ip/32). Read the plan, then type yes."
terraform -chdir=infra/cluster init -input=false -backend-config="bucket=$(state_bucket)" >/dev/null
terraform -chdir=infra/cluster apply -var "api_cidr=$ip/32"

aws eks update-kubeconfig --name sports-notifier --alias "$AWS_CONTEXT" >/dev/null
echo "== kubectl context $AWS_CONTEXT"
kc get nodes -L topology.kubernetes.io/zone

echo "Cluster ready (the app install is not wired in yet). It is billing now: when you're done, run make aws-down."

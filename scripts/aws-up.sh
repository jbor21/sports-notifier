#!/usr/bin/env bash
# make aws-up [TAG=<image tag>]: the cluster from nothing, or an update of a running one (spec,
# section 3). Terraform shows its plan and waits for "yes"; every other step is safe to repeat.
# Checks come first, so nothing starts billing for a deploy that cannot work.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/aws-lib.sh
: "${STRIMZI_CHART:=oci://quay.io/strimzi-helm/strimzi-kafka-operator}" "${STRIMZI_VERSION:=1.2.0}" "${TRAEFIK_VERSION:=42.1.3}"

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

terraform -chdir=infra/base init -input=false -backend-config="bucket=$(state_bucket)" >/dev/null
ecr_url=$(terraform -chdir=infra/base output -raw ecr_url)
fqdn=$(terraform -chdir=infra/base output -raw fqdn)

# Disks: gp3, made in the zone where each pod lands, and the only default class.
echo "== storage class gp3"
kc apply -f deploy/aws/storageclass.yaml
for sc in $(kc get storageclass -o name); do
  [[ "$sc" == storageclass.storage.k8s.io/gp3 ]] && continue
  kc annotate "$sc" storageclass.kubernetes.io/is-default-class- >/dev/null 2>&1 || true
done

# Strimzi, the same install as make cluster; Traefik behind the load balancer's node ports.
echo "== Strimzi $STRIMZI_VERSION"
helm upgrade --install strimzi "$STRIMZI_CHART" --version "$STRIMZI_VERSION" --kube-context "$AWS_CONTEXT" \
  -n strimzi --create-namespace --set watchNamespaces='{default}' --wait --timeout 5m
echo "== Traefik $TRAEFIK_VERSION"
helm upgrade --install traefik traefik --repo https://traefik.github.io/charts --version "$TRAEFIK_VERSION" \
  --kube-context "$AWS_CONTEXT" -n traefik --create-namespace -f deploy/aws/traefik-values.yaml --wait --timeout 5m

# Does ESPN answer this cluster's address? If not, the app still goes in, with the poller off: stopping
# here would leave a half-built cluster billing.
echo "== ESPN check from inside the cluster"
kc delete job espn-check --ignore-not-found >/dev/null
kc create configmap espn-check --from-file=scripts/espn-check.sh --dry-run=client -o yaml | kc apply -f - >/dev/null
kc apply -f deploy/aws/espn-check-job.yaml >/dev/null
espn_ok=0
for _ in $(seq 1 36); do
  if kc wait --for=condition=complete job/espn-check --timeout=5s >/dev/null 2>&1; then espn_ok=1; break; fi
  if kc wait --for=condition=failed job/espn-check --timeout=1s >/dev/null 2>&1; then break; fi
done
kc logs job/espn-check 2>/dev/null || true
poller=()
if (( ! espn_ok )); then
  echo "WARNING: ESPN refused requests from AWS (table above). The app is installed with the ESPN poller off;"
  echo "WARNING: replays and the simulator still work. A poller at home is plan B (DESIGN.md decision 10)."
  poller=(--set espnPoller.enabled=false)
fi

echo "== the app, image $TAG"
helm upgrade --install sports-notifier deploy/helm/sports-notifier --kube-context "$AWS_CONTEXT" \
  -f deploy/helm/sports-notifier/values-aws.yaml \
  --set "image.repository=$ecr_url" --set "image.tag=$TAG" ${poller[@]+"${poller[@]}"} --wait --timeout 15m
kc wait kafka/sports-notifier --for=condition=Ready --timeout=10m

# Through the load balancer, the certificate and Cloudflare's record: what a browser does.
echo "== waiting for https://$fqdn/health"
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "https://$fqdn/health" 2>/dev/null && break
  sleep 10
done
curl -fsS -o /dev/null "https://$fqdn/health" || { echo "https://$fqdn/health did not answer within 10 minutes" >&2; exit 1; }

echo "Live at https://$fqdn"
echo "It is billing now, about \$0.45 an hour. When you're done: make aws-down"

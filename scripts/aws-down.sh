#!/usr/bin/env bash
# make aws-down: save Postgres, then remove everything that bills by the hour (spec, section 5.1).
# Nothing is destroyed unless the dump passed its checks (scripts/aws-backup.sh). Terraform shows what
# it will destroy and waits for "yes"; the sweep at the end says whether anything is still billing.
#   SKIP_DUMP=1 make aws-down   only for a cluster that never got Postgres (an aws-up that failed early):
#                               dumps/latest.dump is left as it was.
set -euo pipefail
cd "$(dirname "$0")/.."
source scripts/aws-lib.sh

aws_require_session
cf_token

if [[ "${SKIP_DUMP:-}" == 1 ]]; then
  echo "== SKIP_DUMP=1: no dump taken; dumps/latest.dump is unchanged"
elif ! scripts/aws-backup.sh; then
  echo "Nothing destroyed. If this cluster never got the app (an aws-up that stopped early), there is" >&2
  echo "nothing to save: SKIP_DUMP=1 make aws-down" >&2
  exit 1
fi

# The cluster may be mid-create (an aws-up cancelled during the apply: AWS finishes the cluster anyway),
# and AWS can't delete one until it is ACTIVE.
status=$(aws eks describe-cluster --name sports-notifier --query cluster.status --output text 2>/dev/null) || status=NONE
if [[ "$status" == CREATING ]]; then
  echo "== EKS is still being created; waiting until it is ACTIVE (AWS can't delete it before then, ~10 min)"
  aws eks wait cluster-active --name sports-notifier
  status=ACTIVE
fi

# Volumes made from claims (Kafka, Postgres, Redis) are not in Terraform's state: delete them through
# Kubernetes while it is still here, or they outlive the cluster and bill by the gigabyte-month.
if [[ "$status" == ACTIVE ]] && aws eks update-kubeconfig --name sports-notifier --alias "$AWS_CONTEXT" >/dev/null 2>&1; then
  if ! kc get namespaces >/dev/null 2>&1; then
    echo "The cluster is up but its API doesn't answer this Mac (it only admits the address aws-up saw)." >&2
    echo "Nothing destroyed: volumes may exist that only Kubernetes can delete. Run make aws-up to refresh" >&2
    echo "the allowed address, then make aws-down again." >&2
    exit 1
  fi
  echo "== uninstall the app and Traefik, delete the volume claims"
  # Topics first, while the topic operator (inside the Kafka cluster) can still clear their
  # finalizers; after the uninstall removes Kafka, nothing would, and the uninstall waits forever.
  kc delete kafkatopic --all --wait=true --timeout=2m || true
  for t in $(kc get kafkatopic -o name 2>/dev/null); do
    kc patch "$t" --type=merge -p '{"metadata":{"finalizers":null}}' || true
  done
  helm uninstall sports-notifier --kube-context "$AWS_CONTEXT" --wait 2>/dev/null || true
  helm uninstall traefik --kube-context "$AWS_CONTEXT" -n traefik --wait 2>/dev/null || true
  kc delete pvc --all --wait=true --timeout=5m
  for _ in $(seq 1 60); do
    [[ -z "$(kc get pv -o name 2>/dev/null)" ]] && break
    sleep 5
  done
  if [[ -n "$(kc get pv -o name)" ]]; then
    echo "Volumes still attached after 5 minutes (kubectl --context $AWS_CONTEXT get pv). Not destroying." >&2
    exit 1
  fi
else
  echo "== no Kubernetes to clean up (EKS: $status)"
fi

ip=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
echo "== terraform destroy in infra/cluster. Read what it will remove, then type yes."
terraform -chdir=infra/cluster init -input=false -backend-config="bucket=$(state_bucket)" >/dev/null
terraform -chdir=infra/cluster destroy -var "api_cidr=$ip/32"

echo "== anything left?"
scripts/aws-leftovers.sh

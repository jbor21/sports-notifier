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
else
  scripts/aws-backup.sh
fi

# Volumes made from claims (Kafka, Postgres, Redis) are not in Terraform's state: delete them through
# Kubernetes while it is still here, or they outlive the cluster and bill by the gigabyte-month.
echo "== uninstall the app and Traefik, delete the volume claims"
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

ip=$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')
echo "== terraform destroy in infra/cluster. Read what it will remove, then type yes."
terraform -chdir=infra/cluster init -input=false -backend-config="bucket=$(state_bucket)" >/dev/null
terraform -chdir=infra/cluster destroy -var "api_cidr=$ip/32"

echo "== anything left?"
scripts/aws-leftovers.sh

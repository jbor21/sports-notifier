#!/usr/bin/env bash
# Laptop only: proves Postgres keeps its data across `make down && make cluster up`.
# It deletes and recreates the k3d cluster twice, so run it when the cluster holds nothing you need.
# Checks: the image can own the mounted folder, the schema exists, and a marker row survives a rebuild.
set -euo pipefail

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

fail() { echo "PERSISTENCE FAIL: $*" >&2; exit 1; }
psql_() { k exec statefulset/postgres -- psql -U sportsnotifier -d sportsnotifier -tA "$@"; }

rebuild() {
  make down
  make cluster up
  k rollout status statefulset/postgres --timeout=180s
}

rebuild

[ "$(k get pod postgres-0 -o jsonpath='{.status.containerStatuses[0].ready}')" = true ] || fail "postgres pod not Ready"
if k logs statefulset/postgres | grep -Eq 'Permission denied|Operation not permitted'; then
  fail "Postgres could not use the mounted folder; run it as the folder's owner (securityContext runAsUser/fsGroup)"
fi
tables=$(psql_ -c '\dt')
for t in users follows notifications notification_reads; do
  echo "$tables" | grep -q "|$t|" || fail "table $t missing"
done

# A plain table, not part of the schema: the schema is recreated by migrations, a marker is not.
marker="marker-$(date +%s)"
psql_ -c "CREATE TABLE IF NOT EXISTS persistence_marker (v text)" -c "INSERT INTO persistence_marker VALUES ('$marker')" >/dev/null

rebuild

[ "$(psql_ -c "SELECT count(*) FROM persistence_marker WHERE v = '$marker'")" = 1 ] \
  || fail "marker row is gone after make down && make cluster up"
psql_ -c "DROP TABLE persistence_marker" >/dev/null
echo "PERSISTENCE OK"

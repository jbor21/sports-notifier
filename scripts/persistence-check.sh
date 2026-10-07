#!/usr/bin/env bash
# Laptop only: proves Postgres keeps its data across `make down && make cluster up`.
# It deletes and recreates the k3d cluster twice, so run it when the cluster holds nothing you need.
# Checks: the image can own the mounted folder, the schema exists, a marker row survives a rebuild,
# and an account made before the rebuild logs in after it with the same password.
set -euo pipefail

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root"

BASE_URL=${BASE_URL:-http://localhost:8080}
fail() { echo "PERSISTENCE FAIL: $*" >&2; exit 1; }
psql_() { k exec statefulset/postgres -- psql -U sportsnotifier -d sportsnotifier -tA "$@"; }

rebuild() {
  make down
  make cluster up
  k rollout status statefulset/postgres --timeout=180s
  for _ in $(seq 1 60); do curl -fsS "$BASE_URL/health" >/dev/null 2>&1 && break; sleep 2; done
}

# POSTs a username and password; prints the HTTP status.
auth() {  # $1 = signup|login
  curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$password\"}" "$BASE_URL/api/v1/auth/$1"
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
user="persist_$(printf '%06d' $RANDOM)"
password="persist-$RANDOM-$RANDOM"
[ "$(auth signup)" = 201 ] || fail "signup before the rebuild failed"

rebuild

[ "$(psql_ -c "SELECT count(*) FROM persistence_marker WHERE v = '$marker'")" = 1 ] \
  || fail "marker row is gone after make down && make cluster up"
psql_ -c "DROP TABLE persistence_marker" >/dev/null
[ "$(auth login)" = 200 ] || fail "$user could not log in after make down && make cluster up"
echo "PERSISTENCE OK"

#!/usr/bin/env bash
# Laptop only: proves Postgres keeps its data across `make down && make cluster up`.
# It deletes and recreates the k3d cluster twice, so run it when the cluster holds nothing you need.
# Checks: the image can own the mounted folder, the schema exists, a marker row survives a rebuild,
# and an account made before the rebuild logs in after it with the same password, still has its
# follow, and gets a simulated play: the sub:* sets were rebuilt from Postgres into the new, empty Redis.
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

jar=$(mktemp)
trap 'rm -f "$jar"' EXIT

# POSTs a username and password; prints the HTTP status. The session cookie lands in $jar.
auth() {  # $1 = signup|login
  curl -s -o /dev/null -w '%{http_code}' -c "$jar" -H 'Content-Type: application/json' \
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
# mlb-hou is the away team in the simulator's game-BOS-HOU.
[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -H 'Content-Type: application/json' \
  -d '{"target":{"kind":"team","id":"mlb-hou"}}' "$BASE_URL/api/v1/follows")" = 201 ] || fail "follow before the rebuild failed"

rebuild

[ "$(psql_ -c "SELECT count(*) FROM persistence_marker WHERE v = '$marker'")" = 1 ] \
  || fail "marker row is gone after make down && make cluster up"
psql_ -c "DROP TABLE persistence_marker" >/dev/null
[ "$(auth login)" = 200 ] || fail "$user could not log in after make down && make cluster up"
[ "$(curl -fsS -b "$jar" "$BASE_URL/api/v1/follows" | jq -r '.items[0].target.id')" = mlb-hou ] \
  || fail "$user's follow is gone after the rebuild"

# Redis is new and empty; user-pref rebuilt the sets from Postgres, so a play reaches the follower.
job=$(scripts/simulate.sh SIM_WAVES=1)
got=0
for _ in $(seq 1 90); do
  if curl -fsS -b "$jar" "$BASE_URL/api/v1/notifications?game=game-BOS-HOU" \
      | jq -e 'any(.items[]; .headline.type == "GAME_START")' >/dev/null; then got=1; break; fi
  sleep 1
done
[ "$got" = 1 ] || fail "no game start reached $user after the rebuild (simulator job $job): were the sets rebuilt?"
echo "PERSISTENCE OK"

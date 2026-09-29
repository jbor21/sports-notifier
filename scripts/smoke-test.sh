#!/usr/bin/env bash
# End-to-end check through the gateway, the way a person uses the app:
# sign up, follow a team, run the simulator, and wait for the notification in history.
set -euo pipefail

# Always the k3d cluster, whatever kubectl's current context is.
KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL=${BASE_URL:-http://localhost:8080}
jar=$(mktemp)

fail() {
  trap - ERR
  echo "SMOKE FAIL: $*" >&2
  make -C "$repo_root" logs >&2 || true
  exit 1
}
trap 'fail "command failed on line $LINENO"' ERR
trap 'rm -f "$jar"' EXIT

# 1. Wait for the cluster and the route through Traefik.
k -n kube-system rollout status deployment/traefik --timeout=180s
k wait --for=condition=available deployment -l app.kubernetes.io/part-of=sports-notifier --timeout=180s
k rollout status statefulset/redis --timeout=180s
for _ in $(seq 1 60); do
  curl -fsS "$BASE_URL/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "$BASE_URL/health" >/dev/null || fail "gateway not reachable at $BASE_URL/health"

# 2. Sign up a fresh user; the session cookie lands in $jar.
user="smoke-$(date +%s)-$RANDOM"
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$jar" -H 'Content-Type: application/json' \
  -d "{\"username\":\"$user\",\"password\":\"smoke-password\"}" "$BASE_URL/api/signup")
[[ "$code" == 200 ]] || fail "signup returned HTTP $code"

# 3. Follow LAL with GAME_START only. Game start is tagged with the home team (LAL in game-LAL-BOS);
#    score events use a random scorer and fan-out only matches the tagged team, so relying on
#    scores would be flaky. Phase 2 fixes fan-out and tightens this to require a score too.
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -X PUT -H 'Content-Type: application/json' \
  -d '{"followedTeams":["LAL"],"followedGames":[],"followedLeagues":[],"notificationTypes":["GAME_START"]}' \
  "$BASE_URL/api/preferences")
[[ "$code" == 200 ]] || fail "preferences returned HTTP $code"

# 4. Play one wave of games. Stop any simulator still running first: every run reuses the same
#    game ids and stamps events with its own pod's clock, so an overlapping run can make the
#    Lua script reject our GAME_START as stale (it is older than the other run's latest event).
k delete jobs -l app.kubernetes.io/name=simulator --cascade=foreground --wait=true --ignore-not-found >/dev/null
job=$("$repo_root/scripts/simulate.sh" SIM_WAVES=1)
echo "simulator job: $job"

# 5. The game start is the first event sent, so it should reach history within seconds.
for _ in $(seq 1 60); do
  if curl -fsS -b "$jar" "$BASE_URL/api/history" \
      | jq -e 'any(.[]; .gameId == "game-LAL-BOS" and (.message | contains("has started")))' >/dev/null; then
    echo "SMOKE PASS"
    exit 0
  fi
  sleep 1
done
fail "No GAME_START for game-LAL-BOS in history after 60s"

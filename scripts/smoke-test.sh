#!/usr/bin/env bash
# End-to-end check through the gateway, the way a person uses the app:
# sign up, follow a team, run the simulator, and wait for the notifications in history.
# Also checks one user cannot see another's data through the gateway.
set -euo pipefail

# Always the k3d cluster, whatever kubectl's current context is.
KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
BASE_URL=${BASE_URL:-http://localhost:8080}
jar=$(mktemp)
bob_jar=$(mktemp)
alice_jar=$(mktemp)

fail() {
  trap - ERR
  echo "SMOKE FAIL: $*" >&2
  make -C "$repo_root" logs >&2 || true
  exit 1
}
trap 'fail "command failed on line $LINENO"' ERR
trap 'rm -f "$jar" "$bob_jar" "$alice_jar"' EXIT

# 1. Wait for the cluster and the route through Traefik.
k -n kube-system rollout status deployment/traefik --timeout=180s
k wait --for=condition=available deployment -l app.kubernetes.io/part-of=sports-notifier --timeout=180s
k rollout status statefulset/redis --timeout=180s
for _ in $(seq 1 60); do
  curl -fsS "$BASE_URL/health" >/dev/null 2>&1 && break
  sleep 1
done
curl -fsS "$BASE_URL/health" >/dev/null || fail "gateway not reachable at $BASE_URL/health"

# 2. Sign up Bob and Alice; each session cookie lands in its own jar.
signup() {  # $1 = username, $2 = cookie jar; prints the response body
  local body code
  body=$(mktemp)
  code=$(curl -s -o "$body" -w '%{http_code}' -c "$2" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$1\",\"password\":\"smoke-password-1\"}" "$BASE_URL/api/signup")
  [[ "$code" == 200 ]] || fail "signup $1 returned HTTP $code: $(cat "$body")"
  cat "$body"; rm -f "$body"
}
suffix="$(date +%s)-$RANDOM"
bob_id=$(signup "bob-$suffix" "$bob_jar" | jq -r .userId)
signup "alice-$suffix" "$alice_jar" >/dev/null

# 3. Bob follows BOS, the AWAY team in game-LAL-BOS. Game start is tagged with the home team
#    and scores with whoever scored, so Bob getting both proves fan-out covers both teams.
#    Alice follows nothing.
code=$(curl -s -o /dev/null -w '%{http_code}' -b "$bob_jar" -X PUT -H 'Content-Type: application/json' \
  -d '{"followedTeams":["BOS"],"followedGames":[],"followedLeagues":[],"notificationTypes":["GAME_START","SCORE_UPDATE"]}' \
  "$BASE_URL/api/preferences")
[[ "$code" == 200 ]] || fail "preferences returned HTTP $code"

# 4. Play one wave of games. Stop any simulator still running first: every run reuses the same
#    game ids, and the later run's seq always wins, so an older run still going would only add
#    noise to Bob's history.
k delete jobs -l app.kubernetes.io/name=simulator --cascade=foreground --wait=true --ignore-not-found >/dev/null
job=$("$repo_root/scripts/simulate.sh" SIM_WAVES=1)
echo "simulator job: $job"

# 5. Bob gets the game start and at least one score, whichever team scored.
got=0
for _ in $(seq 1 60); do
  if curl -fsS -b "$bob_jar" "$BASE_URL/api/history" | jq -e '
      any(.[]; .gameId == "game-LAL-BOS" and (.message | contains("has started")))
      and any(.[]; .gameId == "game-LAL-BOS" and (.message | contains("scores!")))' >/dev/null; then
    got=1; break
  fi
  sleep 1
done
[[ "$got" == 1 ]] || fail "Bob (BOS follower) did not get both a start and a score for game-LAL-BOS within 60s"

# 6. Alice sees none of it, and naming Bob's id in the query changes nothing: the gateway
#    takes the user from the session cookie, never from the request.
[[ "$(curl -fsS -b "$alice_jar" "$BASE_URL/api/history" | jq 'length')" == 0 ]] \
  || fail "Alice's history is not empty"
[[ "$(curl -fsS -b "$alice_jar" "$BASE_URL/api/history?userId=$bob_id" | jq 'length')" == 0 ]] \
  || fail "Alice could read Bob's history with ?userId="
code=$(curl -s -o "$jar" -w '%{http_code}' -b "$alice_jar" "$BASE_URL/api/preferences?userId=$bob_id")
if [[ "$code" == 200 ]] && jq -e '(.followedTeams // []) | index("BOS")' "$jar" >/dev/null; then
  fail "Alice could read Bob's preferences with ?userId="
fi

# 7. Network policies. A throwaway pod labelled like the gateway must reach user-pref (proves
#    the check itself works); one with any other label must be refused. Each waits 5 s first:
#    k3s adds a new pod to the firewall's allow lists a moment after it starts, so an
#    immediate call is refused for the wrong reason.
probe_user_pref() {  # $1 = app.kubernetes.io/name label for the probe pod
  k run "netpol-$1-$RANDOM" --rm -i --restart=Never --quiet --image=curlimages/curl:8.10.1 \
    --labels="app.kubernetes.io/name=$1" --command -- \
    sh -c 'sleep 5; curl -sS -m 5 -o /dev/null http://user-pref:8081/health' >/dev/null 2>&1
}
probe_user_pref gateway || fail "a pod labelled as the gateway could not reach user-pref:8081"
if probe_user_pref netpol-probe; then
  fail "a pod outside the allow lists reached user-pref:8081 (network policies not enforced)"
fi

echo "SMOKE PASS"

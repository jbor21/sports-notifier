#!/usr/bin/env bash
# The round trip on AWS (Phase 5 spec, section 5): an account made before `make aws-down` is there
# after the next `make aws-up`, with its password, its follow and its history, and Redis's sub:* sets
# were rebuilt from the restored Postgres, so a new simulated game still reaches it.
#   bash scripts/aws-persistence-check.sh prepare   # cluster up: sign up, follow, one simulated game
#   make aws-down && make aws-up
#   bash scripts/aws-persistence-check.sh verify
# BASE_URL and KUBE_CONTEXT default to the AWS cluster; pointing them at the laptop tests the script.
set -euo pipefail
cd "$(dirname "$0")/.."

mode=${1:-}
[[ $mode == prepare || $mode == verify ]] || { echo "usage: aws-persistence-check.sh prepare|verify" >&2; exit 2; }

export KUBE_CONTEXT=${KUBE_CONTEXT:-sports-notifier-aws}
if [[ -z ${BASE_URL:-} ]]; then
  BASE_URL="https://$(AWS_PROFILE=sports terraform -chdir=infra/base output -raw fqdn)"
fi
state=target/aws-persistence-user
# Fixed, so verify can log in with nothing but the username; the account is a throwaway.
password=persist-check-password
fail() { echo "PERSISTENCE FAIL: $*" >&2; exit 1; }

jar=$(mktemp)
trap 'rm -f "$jar"' EXIT
auth() {  # $1 = signup|login; prints the HTTP status, the session cookie lands in $jar
  curl -s -o /dev/null -w '%{http_code}' -c "$jar" -H 'Content-Type: application/json' \
    -d "{\"username\":\"$user\",\"password\":\"$password\"}" "$BASE_URL/api/v1/auth/$1"
}
# Game starts in the user's history for the Astros' games.
starts() {
  curl -fsS -b "$jar" "$BASE_URL/api/v1/notifications?team=mlb-hou&limit=100" \
    | jq '[.items[] | select(.headline.type == "GAME_START")] | length'
}
# One simulator wave (its Red Sox–Astros game has new game ids every run); waits up to 120 s for the
# user's history to pass $1 game starts.
wave() {  # $1 = game starts before
  local job
  job=$(scripts/simulate.sh SIM_WAVES=1)
  for _ in $(seq 1 120); do
    (( $(starts) > $1 )) && return 0
    sleep 1
  done
  fail "no new game start reached $user's history in 120 s (simulator job $job)"
}

if [[ $mode == prepare ]]; then
  user="persist_$(date +%s)"
  [ "$(auth signup)" = 201 ] || fail "signup failed"
  [ "$(curl -s -o /dev/null -w '%{http_code}' -b "$jar" -H 'Content-Type: application/json' \
    -d '{"target":{"kind":"team","id":"mlb-hou"}}' "$BASE_URL/api/v1/follows")" = 201 ] || fail "follow failed"
  wave 0
  mkdir -p target
  echo "$user $(starts)" > "$state"
  echo "PREPARED $user with $(starts) game start(s) in history. Now: make aws-down && make aws-up, then verify."
  exit 0
fi

[ -s "$state" ] || fail "no $state: run prepare before the teardown"
read -r user before < "$state"
[ "$(auth login)" = 200 ] || fail "$user could not log in: the account did not survive the round trip"
[ "$(curl -fsS -b "$jar" "$BASE_URL/api/v1/follows" | jq -r '.items[0].target.id')" = mlb-hou ] \
  || fail "$user's follow is gone"
[ "$(starts)" = "$before" ] || fail "history changed across the round trip: $before game starts before, $(starts) after"
# Redis is new and empty: this push reaches the user only if user-pref rebuilt sub:* from Postgres.
wave "$before"
echo "PERSISTENCE OK: $user logged in, kept the follow and $before game start(s), and got a new one ($(starts) now)"

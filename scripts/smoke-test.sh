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
carol_jar=$(mktemp)
dave_jar=$(mktemp)

fail() {
  trap - ERR
  echo "SMOKE FAIL: $*" >&2
  make -C "$repo_root" logs >&2 || true
  exit 1
}
trap 'fail "command failed on line $LINENO"' ERR
trap 'rm -f "$jar" "$bob_jar" "$alice_jar" "$carol_jar" "$dave_jar"' EXIT

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
    -d "{\"username\":\"$1\",\"password\":\"smoke-password-1\"}" "$BASE_URL/api/v1/auth/signup")
  [[ "$code" == 201 ]] || fail "signup $1 returned HTTP $code: $(cat "$body")"
  cat "$body"; rm -f "$body"
}
# Usernames are 3-20 characters of a-z, 0-9, and _.
suffix="$(printf '%08d' $((RANDOM * 32768 + RANDOM)))"
bob_id=$(signup "bob_$suffix" "$bob_jar" | jq -r .id)
signup "alice_$suffix" "$alice_jar" >/dev/null
signup "carol_$suffix" "$carol_jar" >/dev/null
signup "dave_$suffix" "$dave_jar" >/dev/null

# 2b. Log in again with the password just set: the account is in Postgres, not only in the session.
code=$(curl -s -o /dev/null -w '%{http_code}' -c "$bob_jar" -H 'Content-Type: application/json' \
  -d "{\"username\":\"bob_$suffix\",\"password\":\"smoke-password-1\"}" "$BASE_URL/api/v1/auth/login")
[[ "$code" == 200 ]] || fail "login returned HTTP $code"
[[ "$(curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/me" | jq -r .id)" == "$bob_id" ]] || fail "/api/v1/me is not Bob"

# 3. Bob follows mlb-hou, the AWAY team in the simulator's Red Sox–Astros game, with the catalog's
#    default switches (game start, runs, final). Game start is tagged with the home team and runs with whoever scored, so Bob
#    getting both proves fan-out covers both teams. Alice follows nhl-bos: wave 1 has no NHL game, but
#    the old bare team id BOS meant the Celtics, the Red Sox, and the Bruins at once, so under it she
#    would have heard about both Boston games. Her history must stay empty.
follow() {  # $1 = cookie jar, $2 = kind, $3 = id
  curl -s -o /dev/null -w '%{http_code}' -b "$1" -H 'Content-Type: application/json' \
    -d "{\"target\":{\"kind\":\"$2\",\"id\":\"$3\"}}" "$BASE_URL/api/v1/follows"
}
[[ "$(follow "$bob_jar" team mlb-hou)" == 201 ]] || fail "following mlb-hou failed"
[[ "$(follow "$alice_jar" team nhl-bos)" == 201 ]] || fail "following nhl-bos failed"
[[ "$(curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/follows" | jq '.items | length')" == 1 ]] || fail "Bob should have 1 follow"

# 3a. Carol and Dave follow nfl-kc (the simulator's Chiefs–49ers game opens with a Chiefs touchdown,
#     then the extra point one snapshot later). Dave turns touchdowns off: the most specific follow's
#     switches decide, so he must get no touchdown, but still the final.
[[ "$(follow "$carol_jar" team nfl-kc)" == 201 ]] || fail "Carol following nfl-kc failed"
[[ "$(follow "$dave_jar" team nfl-kc)" == 201 ]] || fail "Dave following nfl-kc failed"
dave_follow=$(curl -fsS -b "$dave_jar" "$BASE_URL/api/v1/follows" | jq -r '.items[0].id')
code=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH -b "$dave_jar" -H 'Content-Type: application/json' \
  -d '{"alerts":{"TOUCHDOWN":{"enabled":false}}}' "$BASE_URL/api/v1/follows/$dave_follow")
[[ "$code" == 200 ]] || fail "Dave switching touchdowns off returned HTTP $code"

# 3b. Bob opens the app's live socket, like a browser tab. The notification must arrive on it, not
#     only in history: that proves Stage 2 found Bob's gateway in conn:<userId> and published on
#     that gateway's own channel (gateway routing, DESIGN.md decision 13).
ws_out=$(mktemp)
python3 "$repo_root/scripts/ws-listen.py" "${BASE_URL/http/ws}/ws" "$bob_jar" "First pitch" 120 >"$ws_out" 2>&1 &
ws_pid=$!
for _ in $(seq 1 50); do grep -q '^open' "$ws_out" && break; sleep 0.2; done
grep -q '^open' "$ws_out" || fail "Bob's WebSocket did not open: $(cat "$ws_out")"

# 4. Play one wave of games. Stop any simulator still running first: each run has its own game ids
#    (<league>-sim<run start>-<slot>), so an older run still going would only add noise to histories.
k delete jobs -l app.kubernetes.io/name=simulator --cascade=foreground --wait=true --ignore-not-found >/dev/null
job=$("$repo_root/scripts/simulate.sh" SIM_WAVES=1)
echo "simulator job: $job"

# 5. Bob gets the game start and at least one run, whichever team scored.
got=0
for _ in $(seq 1 60); do
  # History is in Postgres now (the history writers); the API's Notification carries the headline.
  if curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/notifications?team=mlb-hou&limit=100" | jq -e '
      any(.items[]; .headline.type == "GAME_START" and (.headline.text | contains("First pitch")))
      and any(.items[]; .headline.type == "RUN_SCORED" and (.headline.text | contains("score")))' >/dev/null; then
    got=1; break
  fi
  sleep 1
done
[[ "$got" == 1 ]] || fail "Bob (mlb-hou follower) did not get both a start and a run for the Red Sox–Astros game within 60s"
wait "$ws_pid" || fail "Bob's open WebSocket did not receive the game start: $(cat "$ws_out")"

# 5b. Carol gets the touchdown, then its extra point as its own push (decision 15's NFL try).
got=0
for _ in $(seq 1 120); do
  if curl -fsS -b "$carol_jar" "$BASE_URL/api/v1/notifications?team=nfl-kc&limit=100" | jq -e '
      ([.items[] | select(.headline.type == "TOUCHDOWN")] | length) >= 1
      and ([.items[] | select(.headline.type == "TRY_RESULT")] | length) >= 1
      and ([.items[] | select(.headline.type == "TOUCHDOWN")][-1].createdAt
           <= [.items[] | select(.headline.type == "TRY_RESULT")][-1].createdAt)' >/dev/null; then
    got=1; break
  fi
  sleep 1
done
[[ "$got" == 1 ]] || fail "Carol (nfl-kc follower) did not get a touchdown followed by its try result within 120s: $(curl -fsS -b "$carol_jar" "$BASE_URL/api/v1/notifications?team=nfl-kc&limit=100" | jq -c '[.items[].headline.type]')"

# 5c. Dave, touchdowns off, gets the final but never a touchdown, as a headline or an extra.
got=0
for _ in $(seq 1 240); do
  if curl -fsS -b "$dave_jar" "$BASE_URL/api/v1/notifications?team=nfl-kc&limit=100" | jq -e 'any(.items[]; .headline.type == "FINAL")' >/dev/null; then
    got=1; break
  fi
  sleep 1
done
[[ "$got" == 1 ]] || fail "Dave (nfl-kc, touchdowns off) never got the final"
curl -fsS -b "$dave_jar" "$BASE_URL/api/v1/notifications?team=nfl-kc&limit=100" | jq -e '
    all(.items[]; .headline.type != "TOUCHDOWN" and all(.extras[]?; .type != "TOUCHDOWN"))' >/dev/null \
  || fail "Dave switched touchdowns off but got one: $(curl -fsS -b "$dave_jar" "$BASE_URL/api/v1/notifications?team=nfl-kc&limit=100" | jq -c '[.items[] | [.headline.type, [.extras[]?.type]]]')"

# 6. Alice sees none of it, and naming Bob's id in the query changes nothing: the gateway
#    takes the user from the session cookie, never from the request.
[[ "$(curl -fsS -b "$alice_jar" "$BASE_URL/api/v1/notifications" | jq '.items | length')" == 0 ]] \
  || fail "Alice's history is not empty (her nhl-bos follow heard about a Boston game in another league?)"
[[ "$(curl -fsS -b "$alice_jar" "$BASE_URL/api/v1/notifications?userId=$bob_id" | jq '.items | length')" == 0 ]] \
  || fail "Alice could read Bob's history with ?userId="
[[ "$(curl -fsS -b "$alice_jar" "$BASE_URL/api/v1/follows?userId=$bob_id" | jq -r '.items[0].target.id')" == nhl-bos ]] \
  || fail "Alice could read Bob's follows with ?userId="
bob_follow=$(curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/follows" | jq -r '.items[0].id')
[[ "$(curl -s -o /dev/null -w '%{http_code}' -b "$alice_jar" "$BASE_URL/api/v1/follows/$bob_follow")" == 404 ]] \
  || fail "Alice could read Bob's follow by id"

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
  # Seen on 2026-10-02: after a k3d stop/start, k3s sometimes doesn't start its network policy
  # controller, so policies exist but nothing enforces them. Another restart fixed it.
  fail "a pod outside the allow lists reached user-pref:8081 (network policies not enforced). If the cluster was just restarted, k3s may not have started its policy controller: check 'docker logs k3d-sports-notifier-server-0 | grep \"network policy controller\"', then 'k3d cluster stop sports-notifier && make cluster'"
fi

# 8. Kafka. Both topics exist in Kafka with the partition counts the chart asks for. The KafkaTopic
#    status has no partition count, but Strimzi marks it Ready only once the real topic matches the
#    spec it last read (observedGeneration), so Ready for the current spec is the answer.
k wait kafka/sports-notifier --for=condition=Ready --timeout=300s >/dev/null \
  || fail "Kafka cluster sports-notifier is not Ready"
for topic_partitions in game-events:12 notifications:48; do
  topic=${topic_partitions%%:*}; want=${topic_partitions##*:}
  k wait "kafkatopic/$topic" --for=condition=Ready --timeout=120s >/dev/null \
    || fail "KafkaTopic $topic is not Ready: $(k get kafkatopic "$topic" -o jsonpath='{.status.conditions}')"
  fields=$(k get kafkatopic "$topic" -o jsonpath='{.spec.partitions} {.metadata.generation} {.status.observedGeneration}')
  read -r got gen seen <<<"$fields"
  [[ "$gen" == "$seen" ]] || fail "KafkaTopic $topic is Ready for an older spec (generation $gen, observed $seen)"
  [[ "$got" == "$want" ]] || fail "topic $topic has $got partitions, expected $want"
done

# 9. Only the Kafka clients reach the broker's client port. Same probe and 5 s wait as step 7.
probe_kafka() {  # $1 = app.kubernetes.io/name label for the probe pod
  k run "netpol-kafka-$1-$RANDOM" --rm -i --restart=Never --quiet --image=curlimages/curl:8.10.1 \
    --labels="app.kubernetes.io/name=$1" --command -- \
    sh -c 'sleep 5; nc -z -w 5 sports-notifier-kafka-bootstrap 9092' >/dev/null 2>&1
}
probe_kafka event-processing || fail "a pod labelled as event-processing could not reach sports-notifier-kafka-bootstrap:9092"
if probe_kafka netpol-probe; then
  fail "a pod outside the allow lists reached sports-notifier-kafka-bootstrap:9092 (network policies not enforced). If the cluster was just restarted, k3s may not have started its policy controller: check 'docker logs k3d-sports-notifier-server-0 | grep \"network policy controller\"', then 'k3d cluster stop sports-notifier && make cluster'"
fi

# 9b. Only the services that use Postgres reach it: user-pref, notification, history. The gateway is
#     ours but has no business there, so it is the refused case. Same probe and 5 s wait as step 7.
probe_postgres() {  # $1 = app.kubernetes.io/name label for the probe pod
  k run "netpol-pg-$1-$RANDOM" --rm -i --restart=Never --quiet --image=curlimages/curl:8.10.1 \
    --labels="app.kubernetes.io/name=$1" --command -- \
    sh -c 'sleep 5; nc -z -w 5 postgres 5432' >/dev/null 2>&1
}
probe_postgres history || fail "a pod labelled as history could not reach postgres:5432"
if probe_postgres gateway; then
  fail "a pod labelled as the gateway reached postgres:5432; only user-pref, notification, and history may"
fi

# 10. Plays sent while the stage workers are down still arrive once they are back: they wait in
#     Kafka, and a group coming back reads from its own bookmark. (auto.offset.reset=earliest, for a
#     group with no bookmark yet, is tested in Stage1WorkerTest.newGroupReadsEarlierPlays: here the
#     workers join their groups before any play is sent, so "latest" would pass too.)
starts_before=$(curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/notifications?team=mlb-hou&limit=100" | jq '[.items[] | select(.headline.type == "GAME_START")] | length')
k scale deploy/stage1-worker deploy/stage2-worker --replicas=0 >/dev/null
k wait pod -l 'app.kubernetes.io/name in (stage1-worker,stage2-worker)' --for=delete --timeout=120s >/dev/null 2>&1 || true
k delete jobs -l app.kubernetes.io/name=simulator --cascade=foreground --wait=true --ignore-not-found >/dev/null
job=$("$repo_root/scripts/simulate.sh" SIM_WAVES=1)
k wait "job/$job" --for=condition=complete --timeout=300s >/dev/null || fail "simulator job $job did not finish while the workers were down"
k scale deploy/stage1-worker deploy/stage2-worker --replicas=1 >/dev/null
got=0
for _ in $(seq 1 90); do
  now=$(curl -fsS -b "$bob_jar" "$BASE_URL/api/v1/notifications?team=mlb-hou&limit=100" | jq '[.items[] | select(.headline.type == "GAME_START")] | length')
  if (( now > starts_before )); then got=1; break; fi
  sleep 1
done
[[ "$got" == 1 ]] || fail "a game start sent while the stage workers were down never reached Bob after they came back"

echo "SMOKE PASS"

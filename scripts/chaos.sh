#!/usr/bin/env bash
# The chaos suite (Phase 4.2 spec, sections 3-4). Each scenario is one checked replay (the 4.1 checker)
# with one fault at 40% of the replay; the checker's verdict is the scenario's verdict. Run through
# `make chaos [SCENARIO=…]`, which sets DEADLINE from the set and speed.
#
# Usage: scripts/chaos.sh <scenario|all>
#   env: SET (evening), SPEED (60), DEADLINE (the checker Job's time limit, required), BASE_URL
# Output: target/chaos/<UTC time>/<scenario>.log and summary.txt. Exits 1 if any scenario failed.
set -euo pipefail
cd "$(dirname "$0")/.."
# shellcheck source=chaos-lib.sh
source scripts/chaos-lib.sh

SET=${SET:-evening}
SPEED=${SPEED:-60}
DEADLINE=${DEADLINE:?DEADLINE is set by make chaos}
BASE_URL=${BASE_URL:-http://localhost:8080}
SCENARIOS=(stage1-crash stage2-crash history-crash gateway-crash gateway-rollout broker-crash stage1-cut stage2-cut)
# A machine dying needs real cloud machines; it runs last, and waits for the cluster to recover itself.
[ "$CHAOS_PLATFORM" != eks ] || SCENARIOS+=(node-loss)

want=${1:-all}
if [ "$want" = all ]; then
  run=("${SCENARIOS[@]}")
else
  if [ "$want" = node-loss ] && [ "$CHAOS_PLATFORM" != eks ]; then
    echo "node-loss needs EKS: it terminates an EC2 machine (KUBE_CONTEXT=sports-notifier-aws)" >&2; exit 2
  fi
  [[ " ${SCENARIOS[*]} " == *" $want "* ]] || { echo "unknown scenario: $want (one of: ${SCENARIOS[*]}, all)" >&2; exit 2; }
  run=("$want")
fi

# node-loss terminates a machine through the AWS CLI about 70 minutes in: check the login now.
if [[ " ${run[*]} " == *" node-loss "* ]]; then
  command -v aws >/dev/null || { echo "chaos: node-loss needs the aws CLI" >&2; exit 2; }
  aws --profile "${AWS_PROFILE:-sports}" sts get-caller-identity >/dev/null 2>&1 \
    || { echo "chaos: node-loss needs an AWS login first: aws sso login --profile ${AWS_PROFILE:-sports}" >&2; exit 2; }
fi

# The scenarios assume a second copy takes over; with one copy a crash only tests a restart.
for d in stage1-worker stage2-worker gateway; do
  n=$(_k get deploy "$d" -o jsonpath='{.spec.replicas}') || { echo "chaos: cannot read deployment/$d" >&2; exit 2; }
  [ "${n:-0}" -ge 2 ] || { echo "chaos: deployment/$d runs $n copies; the suite needs at least 2 (make up restores the chart's)" >&2; exit 2; }
done

out="target/chaos/$(date -u +%Y%m%dT%H%MZ)"
mkdir -p "$out"
summary="$out/summary.txt"
echo "chaos  set $SET  speed ${SPEED}x  $(date -u +%Y-%m-%dT%H:%MZ)" | tee "$summary"
# Fault marks on the Pipeline dashboard, through our own port-forward (none without the observability pod).
chaos_grafana_start

job_pid="" loop_pid="" log=""
# Whatever happens (a failure, Ctrl-C), leave nothing cut and no request loop running. A checker Job
# still running is stopped too; its synthetic users are then removed by `make check-clean`.
cleanup() {
  set +e   # every step runs, whatever fails before it
  [ -z "$loop_pid" ] || kill "$loop_pid" 2>/dev/null
  if ! chaos_heal; then
    echo "chaos: could not heal the cluster; it may still be cut. Run:" >&2
    echo "  kubectl --context $KUBE_CONTEXT delete networkpolicy chaos-cut; kubectl --context $KUBE_CONTEXT label pods -l chaos chaos-" >&2
  fi
  if [ -n "$job_pid" ] && kill -0 "$job_pid" 2>/dev/null; then
    chaos_stop_job "$job_pid" "$log"
  fi
  [ "$CHAOS_PLATFORM" != eks ] || chaos_debug_cleanup
  chaos_grafana_stop
}
trap cleanup EXIT
trap 'exit 130' INT TERM

say() { echo "[chaos +$((SECONDS - t0)) s] $*" >> "$log"; }

# Sleeps in a way a signal can cut short: bash runs a trap only after the foreground command ends,
# but it interrupts `wait`. So an interrupt during a 50 s cut heals at once, not 50 s later.
nap() { sleep "$1" & wait $! || true; }

deployment_of() {
  case $1 in
    stage1-*) echo stage1-worker ;; stage2-*) echo stage2-worker ;; history-*) echo history ;;
    gateway-*) echo gateway ;; broker-*) echo broker ;;
  esac
}

# Each fault sets fault_ok and proof, and returns non-zero if a step of it could not run. It runs
# inside `if`, where errexit is off, so every step that matters checks its own result.
crash() {
  local pod before id="" moved=""
  if [ "$1" = broker-crash ] && [ "$(chaos_brokers | wc -l | tr -d ' ')" -gt 1 ]; then
    # Several brokers: take the one leading the most partitions, so the most leadership has to move.
    id=$(chaos_leaders 2>>"$log" | chaos_most_leaders) && [ -n "$id" ] || { proof="could not read partition leaders"; return 1; }
    pod="$RELEASE-dual-role-$id"
    CHAOS_BROKER_AT_FAULT=$pod
  else
    pod=$(chaos_first_pod "$(deployment_of "$1")") && [ -n "$pod" ] || { proof="no running pod to crash"; return 1; }
  fi
  before=$(chaos_restarts "$pod") || return 1
  say "crash $pod (restarts $before)"
  chaos_crash "$pod" 2>>"$log" || { proof="the runtime could not stop $pod"; return 1; }
  # Every partition it led must get a leader on another broker within 30 s of the kill, and none may be
  # left without. Timed from when the kill returned, so the debug pod's start-up doesn't count.
  [ -z "$id" ] || moved=$(chaos_await_leaders_moved "$id" 30) || moved=""
  if chaos_wait_restarted "$pod" "$before" 2>>"$log"; then
    proof="$pod restarted at +$fault_at s"
  else
    fault_ok=0; proof="fault did not land: $pod did not restart with exit 137"; return
  fi
  if [ -n "$id" ]; then
    if [ -n "$moved" ]; then proof="$proof, leaders moved in $moved s"
    else fault_ok=0; proof="fault did not land: partitions still led by $id after 30 s"; fi
  fi
}

# A rolling restart of the gateways under a request loop through Traefik, as a browser would (#31).
rollout() {
  local old new codes total failed
  old=$(_k get pods -l app.kubernetes.io/name=gateway -o jsonpath='{.items[*].metadata.name}')
  codes="$out/$1.http"
  : > "$codes"
  ( while :; do curl -s -o /dev/null -w '%{http_code}\n' --max-time 5 "$BASE_URL/" >> "$codes" || true; sleep 0.1; done ) &
  loop_pid=$!
  say "rollout restart deployment/gateway (old pods: $old)"
  if ! chaos_rollout gateway; then
    kill "$loop_pid" 2>/dev/null; loop_pid=""
    proof="the rollout did not finish"; return 1
  fi
  chaos_mark "$1" healed
  nap 10
  kill "$loop_pid" 2>/dev/null || true; wait "$loop_pid" 2>/dev/null || true; loop_pid=""
  new=$(chaos_live_pods gateway | tr '\n' ' ')
  total=$(wc -l < "$codes" | tr -d ' ')
  failed=$(grep -cvE '^[234][0-9][0-9]$' "$codes" || true)
  for p in $old; do [[ " $new " != *" $p "* ]] || { fault_ok=0; proof="fault did not land: $p was not replaced"; return; }; done
  [ "$failed" = 0 ] || http_ok=0
  proof="$(echo "$new" | wc -w | tr -d ' ') new pods, $failed of $total requests failed"
}

# A worker cut off from Kafka for 60 s while it keeps Redis; at 50 s its IP must have left the group.
cut() {
  local pod ip group left back before note
  local members
  pod=$(chaos_first_pod "$(deployment_of "$1")") && [ -n "$pod" ] || { proof="no running pod to cut"; return 1; }
  ip=$(chaos_pod_ip "$pod") || return 1
  group=${1%%-*}
  before=$(chaos_restarts "$pod") || before=0
  say "cut $pod ($ip) from Kafka"
  chaos_cut "$pod" || { chaos_heal "$pod"; proof="could not apply the cut to $pod"; return 1; }
  nap 50
  # Proof needs a successful read showing the other copy still in the group and the cut pod gone.
  if ! members=$(chaos_members "$group"); then
    fault_ok=0; proof="could not read group $group at 50 s"
  elif grep -qx "$ip" <<< "$members"; then
    fault_ok=0; proof="fault did not land: $pod still in $group after 50 s"
  elif [ -z "$members" ]; then
    fault_ok=0; proof="group $group was empty at 50 s: the other copy is gone too"
  fi
  left=$((SECONDS - t_run))
  nap 10
  chaos_heal "$pod"
  chaos_mark "$1" healed
  say "healed $pod"
  back=""
  for _ in $(seq 1 30); do
    if chaos_members "$group" | grep -qx "$ip"; then back=$((SECONDS - t_run)); break; fi
    sleep 2
  done
  [ "$fault_ok" = 0 ] && return
  note=$(chaos_restart_note "$pod" "$before")
  [ -z "$note" ] || say "$note"
  if [ -n "$back" ]; then
    proof="$pod left $group by +$left s, back at +$back s"
  else
    proof="$pod left $group by +$left s, not back within 60 s"
  fi
}

# A machine dies (spec 4.3): terminate one EC2 instance, then prove its replacement comes up in the same
# zone and the broker it held is back with nothing under-replicated, within CHAOS_NODE_LOSS_SECONDS (900).
node_loss() {
  local limit=${CHAOS_NODE_LOSS_SECONDS:-900} job checker_node out victim zone iid region before broker="" b
  local t_kill state="" notready="" repl ready_at="" broker_at="" full_at="" urp
  command -v aws >/dev/null || { proof="the aws CLI is not installed"; return 1; }
  job=$(grep -oE '^job/checker-[0-9a-z-]+' "$log" | head -1)
  checker_node=$(_k get pods -l "job-name=${job#job/}" -o jsonpath='{.items[0].spec.nodeName}' 2>>"$log")
  [ -n "$checker_node" ] || { proof="could not find the checker's machine"; return 1; }
  out=$(chaos_pick_victim "$checker_node" 2>>"$log") || { proof="no machine to kill (see the log)"; return 1; }
  victim=$(head -1 <<< "$out")
  [ "$(wc -l <<< "$out")" -lt 2 ] || say "$(sed -n 2p <<< "$out")"
  zone=$(chaos_zone_of "$victim") && iid=$(chaos_instance_of "$victim") && [ -n "$zone" ] && [ -n "$iid" ] \
    || { proof="could not read $victim's zone or instance"; return 1; }
  region=${zone%?}
  before=$(_k get nodes -o jsonpath='{range .items[*]}{.metadata.name}{" "}{end}')
  for b in $(chaos_brokers); do [ "$(chaos_node_of "$b")" = "$victim" ] && broker=$b; done
  [ -n "$broker" ] || { proof="no broker on $victim"; return 1; }
  say "on $victim: $(chaos_pods_on "$victim" | tr '\n' ' ')"
  CHAOS_BROKER_AT_FAULT=$broker
  say "terminate $iid ($victim in $zone, with $broker; checker on $checker_node)"
  t_kill=$SECONDS
  aws --profile "${AWS_PROFILE:-sports}" --region "$region" ec2 terminate-instances --instance-ids "$iid" >>"$log" 2>&1 \
    || { proof="aws could not terminate $iid"; return 1; }

  for _ in $(seq 1 30); do
    state=$(aws --profile "${AWS_PROFILE:-sports}" --region "$region" ec2 describe-instances --instance-ids "$iid" \
      --query 'Reservations[0].Instances[0].State.Name' --output text 2>>"$log" || true)
    [[ "$state" == shutting-down || "$state" == terminated ]] && break
    sleep 2
  done
  [[ "$state" == shutting-down || "$state" == terminated ]] || { fault_ok=0; proof="fault did not land: $iid still ${state:-unknown} after 60 s"; return; }
  for _ in $(seq 1 60); do
    [ "$(_k get node "$victim" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" = True ] \
      || { notready=$((SECONDS - t_kill)); break; }
    sleep 2
  done
  [ -n "$notready" ] || { fault_ok=0; proof="fault did not land: $victim still Ready after 120 s"; return; }
  chaos_mark "$1" "machine down"

  repl=$(chaos_await_replacement "$zone" $((limit - (SECONDS - t_kill))) $before 2>>"$log") \
    || { fault_ok=0; proof="$(tail -1 "$log")"; return; }
  ready_at=$((SECONDS - t_kill))
  say "replacement ${repl% *} Ready at +$ready_at s"
  while [ $((SECONDS - t_kill)) -lt "$limit" ]; do
    if [ -z "$broker_at" ] && chaos_ready "$broker" && [ "$(chaos_node_of "$broker")" = "${repl% *}" ]; then
      broker_at=$((SECONDS - t_kill)); CHAOS_BROKER_AT_FAULT=""
    fi
    if [ -n "$broker_at" ] && urp=$(chaos_under_replicated 2>>"$log") && [ "$urp" = 0 ]; then
      full_at=$((SECONDS - t_kill)); break
    fi
    sleep 5
  done
  chaos_mark "$1" healed
  if [ -z "$full_at" ]; then
    fault_ok=0
    proof="not recovered within $limit s: replacement Ready +$ready_at s, "
    if [ -n "$broker_at" ]; then proof="${proof}broker back +$broker_at s, still under-replicated"; else proof="${proof}broker not back"; fi
    return
  fi
  proof="$iid in $zone: NotReady +$notready s, replacement Ready +$ready_at s, broker back +$broker_at s, fully replicated +$full_at s"
}

passed=0
for name in "${run[@]}"; do
  log="$out/$name.log"
  : > "$log"
  t0=$SECONDS
  fault_ok=1 http_ok=1 proof="" fault_at=0
  CHAOS_BROKER_AT_FAULT=""

  if ! chaos_steady >> "$log" 2>&1; then
    printf '%-16s FAIL  cluster not steady (see %s)\n' "$name" "$log" | tee -a "$summary"
    continue
  fi
  say "steady; starting the checker (set $SET at ${SPEED}x, time limit $DEADLINE s)"
  # Per-scenario flags and env (chaos_job_flags). The ${…+…} form: the Mac's bash 3.2 calls an empty
  # array unbound under set -u.
  flags=($(chaos_job_flags "$name")); envs=($(chaos_job_env "$name"))
  scripts/run-job.sh --deadline "$DEADLINE" ${flags[@]+"${flags[@]}"} checker CHECK_SET="$SET" CHECK_SPEED="$SPEED" \
    ${envs[@]+"${envs[@]}"} >> "$log" 2>&1 &
  job_pid=$!

  started=""
  for _ in $(seq 1 "${CHAOS_START_WAIT:-300}"); do
    started=$(grep -m1 '\[Checker\] run started' "$log" || true)
    [ -z "$started" ] && kill -0 "$job_pid" 2>/dev/null || break
    sleep 1
  done
  rc=0
  if [ -z "$started" ]; then
    # Still running without its run: stop it now rather than wait out the Job's time limit (#54).
    if kill -0 "$job_pid" 2>/dev/null; then chaos_stop_job "$job_pid" "$log"; rc=1; else wait "$job_pid" || rc=$?; fi
    job_pid=""
    printf '%-16s FAIL  the checker never started its run (exit %s; see %s)\n' "$name" "$rc" "$log" | tee -a "$summary"
    continue
  fi
  replay=$(echo "$started" | sed -E 's/.*replay ([0-9]+) s at.*/\1/')
  t_run=$SECONDS
  run_ms=$(chaos_now_ms)
  chaos_mark "$name" "run started" "$run_ms"
  nap $((replay * 40 / 100))
  fault_at=$((SECONDS - t_run))
  chaos_mark "$name" fault
  say "fault at +$fault_at s into a $replay s replay"
  # A fault step that cannot run fails this scenario, never the suite (spec 4.2).
  if ! case $name in
         *-crash) crash "$name" ;;
         gateway-rollout) rollout "$name" ;;
         *-cut) cut "$name" ;;
         node-loss) node_loss "$name" ;;
       esac; then
    fault_ok=0; proof="fault step failed: ${proof:-see the log}"
  fi
  say "$proof"

  wait "$job_pid" || rc=$?
  job_pid=""
  end_ms=$(chaos_now_ms)
  chaos_mark "$name" "run ended" "$end_ms"
  pushes=$(grep -oE 'expected pushes [0-9,]+' "$log" | awk '{print $3}' || true)
  worst=$(grep -oE 'max ([0-9]+ ms|-)' "$log" | tail -1 | awk '{ if ($2 == "-") print "-"; else printf "%.1f s", $2 / 1000 }' || true)
  if [ "$rc" = 0 ] && [ "$fault_ok" = 1 ] && [ "$http_ok" = 1 ]; then
    verdict=PASS; passed=$((passed + 1))
  else
    verdict=FAIL
    [ "$rc" = 0 ] || proof="checker exit $rc; $proof"
    { make -s logs; } > "$out/$name.cluster.log" 2>&1 || true
  fi
  printf '%-16s %s  pushes %s  socket misses %s  worst delay %s  %s\n' \
    "$name" "$verdict" "${pushes:--}" "$(chaos_counts "$log")" "${worst:--}" "$proof" | tee -a "$summary"
  # The scenario's window on the dashboard, for a screenshot (make grafana serves localhost:3000).
  [ "$CHAOS_GRAFANA" != 1 ] || echo "  dashboard: http://localhost:3000/d/pipeline?from=$((run_ms - 30000))&to=$((end_ms + 30000))" | tee -a "$summary"
done

echo "${passed} of ${#run[@]} passed" | tee -a "$summary"
[ "$passed" = "${#run[@]}" ]

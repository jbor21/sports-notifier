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

want=${1:-all}
if [ "$want" = all ]; then
  run=("${SCENARIOS[@]}")
else
  [[ " ${SCENARIOS[*]} " == *" $want "* ]] || { echo "unknown scenario: $want (one of: ${SCENARIOS[*]}, all)" >&2; exit 2; }
  run=("$want")
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
  local pod before
  pod=$(chaos_first_pod "$(deployment_of "$1")") && [ -n "$pod" ] || { proof="no running pod to crash"; return 1; }
  before=$(chaos_restarts "$pod") || return 1
  say "crash $pod (restarts $before)"
  chaos_crash "$pod" 2>>"$log" || { proof="crictl could not stop $pod"; return 1; }
  if chaos_wait_restarted "$pod" "$before" 2>>"$log"; then
    proof="$pod restarted at +$fault_at s"
  else
    fault_ok=0; proof="fault did not land: $pod did not restart with exit 137"
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

passed=0
for name in "${run[@]}"; do
  log="$out/$name.log"
  : > "$log"
  t0=$SECONDS
  fault_ok=1 http_ok=1 proof="" fault_at=0

  if ! chaos_steady >> "$log" 2>&1; then
    printf '%-16s FAIL  cluster not steady (see %s)\n' "$name" "$log" | tee -a "$summary"
    continue
  fi
  say "steady; starting the checker (set $SET at ${SPEED}x, time limit $DEADLINE s)"
  scripts/run-job.sh --deadline "$DEADLINE" checker CHECK_SET="$SET" CHECK_SPEED="$SPEED" >> "$log" 2>&1 &
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

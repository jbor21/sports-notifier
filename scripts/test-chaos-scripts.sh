#!/usr/bin/env bash
# Tests for the chaos and job scripts' failure paths, with fake kubectl and docker on the PATH (no
# cluster needed). Run: bash scripts/test-chaos-scripts.sh
set -uo pipefail
cd "$(dirname "$0")/.."
root=$(pwd)
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT
failed=0

ok()   { echo "ok   $1"; }
bad()  { echo "FAIL $1: $2"; failed=1; }

# A fake kubectl whose behavior is the shell snippet in $1 (it sees the arguments as "$@").
fake_kubectl() {
  mkdir -p "$tmp/bin"
  printf '#!/usr/bin/env bash\n%s\n' "$1" > "$tmp/bin/kubectl"
  printf '#!/usr/bin/env bash\nexit 1\n' > "$tmp/bin/docker"
  chmod +x "$tmp/bin/kubectl" "$tmp/bin/docker"
}

# A failed read of a consumer group must not look like "the cut pod left the group".
fake_kubectl 'echo "Unable to connect to the server" >&2; exit 1'
if PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_members stage2" >/dev/null 2>&1; then
  bad members_read_failure "chaos_members succeeded although kubectl failed"
else
  ok members_read_failure
fi

# If healing fails on the way out, chaos.sh says so and prints the commands to heal by hand. The
# fake API answers only the two-copies check, then fails everything, healing included.
fake_kubectl 'case "$*" in *"get deploy"*"spec.replicas"*) echo 2 ;; *) echo "Unable to connect to the server" >&2; exit 1 ;; esac'
err=$(PATH="$tmp/bin:$PATH" DEADLINE=60 CHAOS_STEADY_SECONDS=0 bash scripts/chaos.sh stage2-cut 2>&1 >/dev/null)
if [[ "$err" == *"delete networkpolicy chaos-cut"* && "$err" == *"label pods -l chaos chaos-"* ]]; then
  ok cleanup_reports_failed_heal
else
  bad cleanup_reports_failed_heal "no manual heal commands in: $err"
fi

# A brief API error while waiting for the Job is retried, not reported as the Job failing (#49).
fake_kubectl '
state='"$tmp"'/getjob-count
case "$1 $2 $3" in
  "--context ctx create") echo "job.batch/x created" ;;
  "--context ctx wait") exit 1 ;;
  "--context ctx logs") echo "a log line" ;;
  "--context ctx get")
    if [ "$4" = job ]; then
      n=$(cat "$state" 2>/dev/null || echo 0); echo $((n + 1)) > "$state"
      [ "$n" = 0 ] && { echo "TLS handshake timeout" >&2; exit 1; }
      echo "Complete:CompletionsReached "
    else
      echo 0
    fi ;;
esac'
PATH="$tmp/bin:$PATH" KUBE_CONTEXT=ctx bash scripts/run-job.sh checker >/dev/null 2>&1
rc=$?
[ "$rc" = 0 ] && ok run_job_retries_transient_get || bad run_job_retries_transient_get "exit $rc, want 0"

# A Job stopped by the chart's default limit (no --deadline) says so without an empty number.
rm -f "$tmp/getjob-count"
fake_kubectl '
case "$1 $2 $3" in
  "--context ctx create") echo "job.batch/x created" ;;
  "--context ctx wait") exit 1 ;;
  "--context ctx get") [ "$4" = job ] && echo "Failed:DeadlineExceeded " ;;
esac'
err=$(PATH="$tmp/bin:$PATH" KUBE_CONTEXT=ctx bash scripts/run-job.sh checker 2>&1 >/dev/null)
if [[ "$err" == *"time limit reached"* && "$err" != *"( s)"* ]]; then
  ok run_job_default_limit_message
else
  bad run_job_default_limit_message "got: $err"
fi

# Fractional speeds worked before time limits existed; they still do, and a zero speed is refused.
out=$(make -n check SET=ci SPEED=2.5 2>&1)
[[ "$out" == *"--deadline 4164 "* ]] && ok deadline_fractional_speed || bad deadline_fractional_speed "$out"
out=$(make -n check SET=ci SPEED=0 2>&1)
[[ "$out" == *"SPEED must be a positive number"* ]] && ok deadline_zero_speed || bad deadline_zero_speed "$out"

# --- Phase 4.3: fault marks on the dashboard, and #54 items 1, 3, 4 ---

# A fake curl that records its arguments and exits with $CURL_EXIT (default 0).
fake_curl() {
  printf '#!/usr/bin/env bash\necho "$*" >> "%s/curl-calls"\nexit ${CURL_EXIT:-0}\n' "$tmp" > "$tmp/bin/curl"
  chmod +x "$tmp/bin/curl"
  rm -f "$tmp/curl-calls"
}

# A mark that cannot be posted is one log line, never a failed scenario.
fake_kubectl 'exit 0'; fake_curl
out=$(PATH="$tmp/bin:$PATH" CURL_EXIT=7 bash -c "source '$root/scripts/chaos-lib.sh'; CHAOS_GRAFANA=1; chaos_mark stage1-crash fault" 2>&1)
rc=$?
if [ "$rc" = 0 ] && [ "$(printf '%s\n' "$out" | grep -c .)" = 1 ]; then ok mark_failure_is_not_fatal; else bad mark_failure_is_not_fatal "exit $rc, output: $out"; fi

# No observability pod: no port-forward, no marks.
fake_kubectl 'case "$*" in *"get svc lgtm"*) echo "NotFound" >&2; exit 1 ;; esac'; fake_curl
out=$(PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_grafana_start; echo \"grafana=\$CHAOS_GRAFANA\"; chaos_mark stage1-crash fault" 2>&1)
if [[ "$out" == *"grafana=0"* ]] && [ ! -e "$tmp/curl-calls" ]; then ok no_lgtm_skips_marks; else bad no_lgtm_skips_marks "$out; curl: $(cat "$tmp/curl-calls" 2>/dev/null)"; fi

# #54.1: a pod restarted by an earlier crash scenario was not "restarted during the cut".
fake_kubectl 'echo 1'
out=$(PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_restart_note stage1-worker-a 1")
[ -z "$out" ] && ok cut_restart_note_uses_count_before || bad cut_restart_note_uses_count_before "got: $out"
out=$(PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_restart_note stage1-worker-a 0")
[[ "$out" == *"restarted during the cut"* ]] && ok cut_restart_note_reports_new_restart || bad cut_restart_note_reports_new_restart "got: $out"

# #54.4: a pod being deleted is not one of the rollout's new pods.
fake_kubectl 'printf "gateway-new-1 \ngateway-old-2 2026-10-13T10:00:00Z\ngateway-new-3 \n"'
out=$(PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_live_pods gateway" | tr '\n' ' ')
[ "$out" = "gateway-new-1 gateway-new-3 " ] && ok rollout_ignores_terminating || bad rollout_ignores_terminating "got: $out"

# #54.3: a checker that never printed "run started" is stopped and its Job deleted, not waited out.
fake_kubectl 'echo "$*" >> '"$tmp"'/kubectl-calls'
rm -f "$tmp/kubectl-calls"
printf 'job/checker-123-abc (time limit 900 s)\n' > "$tmp/scenario.log"
start=$SECONDS
out=$(PATH="$tmp/bin:$PATH" bash -c "source '$root/scripts/chaos-lib.sh'; sleep 60 & pid=\$!; chaos_stop_job \$pid '$tmp/scenario.log'; kill -0 \$pid 2>/dev/null && echo alive || echo stopped")
if [ "$out" = stopped ] && [ $((SECONDS - start)) -lt 10 ] && grep -q "delete job/checker-123-abc" "$tmp/kubectl-calls" 2>/dev/null; then
  ok missing_run_started_deletes_job
else
  bad missing_run_started_deletes_job "got: $out; kubectl: $(cat "$tmp/kubectl-calls" 2>/dev/null)"
fi

# --- Phase 6.1 ---

# The summary line counts the pushes the silent-socket rule excused, apart from the others.
printf 'sockets   missing 0 (allowed in disconnects: 3, silent: 2)   duplicate 0\n' > "$tmp/report.log"
out=$(bash -c "source '$root/scripts/chaos-lib.sh'; chaos_counts '$tmp/report.log'")
[ "$out" = "excused 3  silent 2" ] && ok summary_shows_silent || bad summary_shows_silent "got: $out"
printf 'no report here\n' > "$tmp/report.log"
out=$(bash -c "source '$root/scripts/chaos-lib.sh'; chaos_counts '$tmp/report.log'")
[ "$out" = "excused -  silent -" ] && ok summary_counts_missing_report || bad summary_counts_missing_report "got: $out"

# The platform comes from the kubectl context: k3d's contexts are k3d-<name>, anything else is EKS.
out=$(KUBE_CONTEXT=k3d-x bash -c "source '$root/scripts/chaos-lib.sh'; echo \$CHAOS_PLATFORM")
out2=$(KUBE_CONTEXT=sports-notifier-aws bash -c "source '$root/scripts/chaos-lib.sh'; echo \$CHAOS_PLATFORM")
[ "$out $out2" = "k3d eks" ] && ok platform_from_context || bad platform_from_context "got: $out $out2"

# A debug pod that never finishes fails the node command, names the pod, and is deleted anyway.
fake_kubectl 'echo "$*" >> '"$tmp"'/kubectl-calls
case "$*" in
  *" debug node/"*) echo "Creating debugging pod node-debugger-n1-abcde with container debugger on node n1." ;;
  *"get pod node-debugger-n1-abcde"*"phase"*) echo Running ;;
esac'
rm -f "$tmp/kubectl-calls"
err=$(PATH="$tmp/bin:$PATH" KUBE_CONTEXT=eks CHAOS_NODE_RUN_SECONDS=1 bash -c "source '$root/scripts/chaos-lib.sh'; chaos_node_run n1 'true'" 2>&1 >/dev/null)
rc=$?
if [ "$rc" != 0 ] && [[ "$err" == *"node-debugger-n1-abcde did not finish"* ]] && grep -q "delete pod node-debugger-n1-abcde" "$tmp/kubectl-calls"; then
  ok node_run_debug_pod_never_finishes
else
  bad node_run_debug_pod_never_finishes "exit $rc, err: $err, calls: $(cat "$tmp/kubectl-calls" 2>/dev/null)"
fi

# On EKS a crash is crictl in the machine's own root, through a debug pod on the pod's machine; no docker.
fake_kubectl 'echo "$*" >> '"$tmp"'/kubectl-calls
case "$*" in
  *"get pod p "*"containerID"*) echo "containerd://abc123" ;;
  *"get pod p "*"nodeName"*) echo n1 ;;
  *" debug node/"*) echo "Creating debugging pod node-debugger-n1-xyz12 with container debugger on node n1." ;;
  *"get pod node-debugger-n1-xyz12"*"phase"*) echo Succeeded ;;
  *"get pod node-debugger-n1-xyz12"*"exitCode"*) echo 0 ;;
esac'
printf '#!/usr/bin/env bash\necho "$*" >> "%s/docker-calls"\nexit 1\n' "$tmp" > "$tmp/bin/docker"; chmod +x "$tmp/bin/docker"
rm -f "$tmp/kubectl-calls" "$tmp/docker-calls"
PATH="$tmp/bin:$PATH" KUBE_CONTEXT=eks bash -c "source '$root/scripts/chaos-lib.sh'; chaos_crash p" >/dev/null 2>&1
rc=$?
debug=$(grep " debug node/n1 " "$tmp/kubectl-calls" 2>/dev/null)
if [ "$rc" = 0 ] && [[ "$debug" == *"chroot /host"* && "$debug" == *"stop --timeout 0 abc123"* ]] && [ ! -e "$tmp/docker-calls" ]; then
  ok eks_crash_uses_crictl_in_chroot
else
  bad eks_crash_uses_crictl_in_chroot "exit $rc, debug call: $debug, docker: $(cat "$tmp/docker-calls" 2>/dev/null)"
fi

# Whatever happens, chaos.sh on EKS deletes leftover debug pods on the way out (they run privileged).
fake_kubectl 'echo "$*" >> '"$tmp"'/kubectl-calls
case "$*" in
  *"get deploy"*"spec.replicas"*) echo 2 ;;
  *"get pods -o name"*) echo pod/node-debugger-n1-left1; echo pod/gateway-abc ;;
  *) echo "Unable to connect to the server" >&2; exit 1 ;;
esac'
rm -f "$tmp/kubectl-calls"
PATH="$tmp/bin:$PATH" KUBE_CONTEXT=eks DEADLINE=60 CHAOS_STEADY_SECONDS=0 bash scripts/chaos.sh stage2-cut >/dev/null 2>&1
if grep -q "delete pod/node-debugger-n1-left1" "$tmp/kubectl-calls" && ! grep -q "delete pod/gateway-abc" "$tmp/kubectl-calls"; then
  ok cleanup_deletes_debug_pods
else
  bad cleanup_deletes_debug_pods "calls: $(grep delete "$tmp/kubectl-calls" 2>/dev/null)"
fi

# A fake API with three brokers. BROKERS_READY lists which of 0 1 2 are Ready; URP is how many
# partitions --under-replicated-partitions prints. Every call is recorded.
brokers_fake='echo "$*" >> '"$tmp"'/kubectl-calls
case "$*" in
  *"get pods -l strimzi.io/pool-name=dual-role"*) printf "sports-notifier-dual-role-0\nsports-notifier-dual-role-1\nsports-notifier-dual-role-2\n" ;;
  *"get pod sports-notifier-dual-role-"*"Ready"*) a="$*"; n=${a##*dual-role-}; n=${n%% *}; [[ " $BROKERS_READY " == *" $n "* ]] && echo True || echo False ;;
  *"get deploy -l"*) printf "stage1-worker 2 2\nstage2-worker 2 2\nhistory 1 1\n" ;;
  *"under-replicated-partitions"*) for i in $(seq 1 ${URP:-0}); do printf "\tTopic: notifications\tPartition: $i\tLeader: 1\n"; done ;;
  *"exec"*"kafka-consumer-groups"*) echo "GROUP CONSUMER-ID HOST CLIENT-ID #PARTITIONS"; echo "stage1 c1 /10.0.0.1 c 6" ;;
esac'

# Admin commands go to a Ready broker that is not the one at fault.
fake_kubectl "$brokers_fake"; rm -f "$tmp/kubectl-calls"
PATH="$tmp/bin:$PATH" BROKERS_READY="0 1 2" CHAOS_BROKER_AT_FAULT=sports-notifier-dual-role-0 \
  bash -c "source '$root/scripts/chaos-lib.sh'; chaos_members stage1" >/dev/null 2>&1
if grep "kafka-consumer-groups" "$tmp/kubectl-calls" | grep -q "exec sports-notifier-dual-role-1 "; then
  ok admin_skips_broker_at_fault
else
  bad admin_skips_broker_at_fault "calls: $(grep exec "$tmp/kubectl-calls" 2>/dev/null)"
fi

# With no Ready broker left, admin commands (and so group reads) fail rather than guess.
fake_kubectl "$brokers_fake"
if PATH="$tmp/bin:$PATH" BROKERS_READY="" bash -c "source '$root/scripts/chaos-lib.sh'; chaos_members stage1" >/dev/null 2>&1; then
  bad admin_fails_with_no_ready_broker "chaos_members succeeded with no Ready broker"
else
  ok admin_fails_with_no_ready_broker
fi

# Steady needs every broker Ready and nothing under-replicated.
fake_kubectl "$brokers_fake"
err=$(PATH="$tmp/bin:$PATH" BROKERS_READY="0 1" CHAOS_STEADY_SECONDS=0 bash -c "source '$root/scripts/chaos-lib.sh'; chaos_steady" 2>&1)
err2=$(PATH="$tmp/bin:$PATH" BROKERS_READY="0 1 2" URP=4 CHAOS_STEADY_SECONDS=0 bash -c "source '$root/scripts/chaos-lib.sh'; chaos_steady" 2>&1)
if [[ "$err" == *"broker sports-notifier-dual-role-2 not ready"* && "$err2" == *"4 under-replicated"* ]]; then
  ok steady_waits_for_all_brokers_and_isr
else
  bad steady_waits_for_all_brokers_and_isr "got: $err | $err2"
fi

# The broker crash takes the broker that leads the most partitions (ties: the lowest id); leaderless
# partitions count for nobody.
out=$(printf 'game-events 0 1\ngame-events 1 2\nnotifications 0 2\nnotifications 1 -1\nnotifications 2 0\n' \
  | bash -c "source '$root/scripts/chaos-lib.sh'; chaos_most_leaders")
out2=$(printf 'game-events 0 1\ngame-events 1 0\n' | bash -c "source '$root/scripts/chaos-lib.sh'; chaos_most_leaders")
[ "$out $out2" = "2 0" ] && ok broker_crash_picks_most_leaders || bad broker_crash_picks_most_leaders "got: $out $out2"

# Leaders come from kafka-topics' describe lines, for the two pipeline topics only; "none" is -1.
out=$(printf 'Topic: game-events\tTopicId: x\tPartitionCount: 2\n\tTopic: game-events\tPartition: 0\tLeader: 1\tReplicas: 1,2,0\n\tTopic: game-events-dlq\tPartition: 0\tLeader: 2\tReplicas: 2\n\tTopic: notifications\tPartition: 5\tLeader: none\tReplicas: 0,1,2\n' \
  | bash -c "source '$root/scripts/chaos-lib.sh'; chaos_parse_leaders" | tr '\n' ';')
[ "$out" = "game-events 0 1;notifications 5 -1;" ] && ok leaders_parsed || bad leaders_parsed "got: $out"

exit $failed

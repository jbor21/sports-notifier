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

exit $failed

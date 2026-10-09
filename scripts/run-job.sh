#!/usr/bin/env bash
# Runs one of the chart's suspended CronJobs (replay, checker) once, as a one-off Job, streams its log,
# and exits with the Job's result: 0 if it succeeded, 1 if it failed or never finished.
#
# Usage: scripts/run-job.sh <cronjob> [KEY=VALUE ...]   (KEY=VALUE args override the Job's env)
set -euo pipefail

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

[ $# -ge 1 ] || { echo "usage: run-job.sh <cronjob> [KEY=VALUE ...]" >&2; exit 2; }
cronjob=$1; shift
for arg in "$@"; do
  [[ "$arg" =~ ^[A-Z_][A-Z0-9_]*= ]] || { echo "not KEY=VALUE: $arg" >&2; exit 2; }
done

name="$cronjob-$(date +%s)-$RANDOM"
if [[ $# -gt 0 ]]; then
  k create job "$name" --from="cronjob/$cronjob" --dry-run=client -o yaml \
    | k set env --local -f - "$@" -o yaml \
    | k create -f - >/dev/null
else
  k create job "$name" --from="cronjob/$cronjob" >/dev/null
fi
echo "job/$name" >&2

# Wait for the pod to start (image already in the cluster), then follow its log to the end.
k wait --for=condition=Ready pod -l job-name="$name" --timeout=120s >/dev/null 2>&1 \
  || k wait --for=jsonpath='{.status.phase}'=Succeeded pod -l job-name="$name" --timeout=5s >/dev/null 2>&1 \
  || k wait --for=jsonpath='{.status.phase}'=Failed pod -l job-name="$name" --timeout=5s >/dev/null 2>&1 || true
k logs -f "job/$name" || true

for _ in $(seq 1 30); do
  if [ "$(k get job "$name" -o jsonpath='{.status.succeeded}')" = "1" ]; then exit 0; fi
  if [ "$(k get job "$name" -o jsonpath='{.status.failed}')" = "1" ]; then exit 1; fi
  sleep 2
done
echo "job/$name did not finish" >&2
exit 1

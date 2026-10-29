#!/usr/bin/env bash
# Runs one of the chart's suspended CronJobs (replay, checker) once, as a one-off Job, streams its log,
# and exits with the Job's result: the pod's own exit code (the checker: 0 PASS, 1 FAIL, 2 SETUP
# FAILED), or 1 if the Job ran out of time or never got a pod.
#
# Usage: scripts/run-job.sh [--deadline SECONDS] [--near POD] <cronjob> [KEY=VALUE ...]
#   --deadline  the Job's time limit (activeDeadlineSeconds); Kubernetes stops it after that long
#   --near      run the Job's pod on the same machine as POD (node-loss keeps the checker off the victim)
#   KEY=VALUE   overrides the Job's env
set -euo pipefail

KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }
usage() { echo "usage: run-job.sh [--deadline SECONDS] [--near POD] <cronjob> [KEY=VALUE ...]" >&2; exit 2; }

deadline="" near=""
while [[ "${1:-}" == --* ]]; do
  case $1 in
    --deadline) [[ "${2:-}" =~ ^[0-9]+$ ]] || usage; deadline=$2; shift 2 ;;
    --near) [ -n "${2:-}" ] || usage; near=$2; shift 2 ;;
    *) usage ;;
  esac
done
[ $# -ge 1 ] || usage
cronjob=$1; shift
for arg in "$@"; do
  [[ "$arg" =~ ^[A-Z_][A-Z0-9_]*= ]] || { echo "not KEY=VALUE: $arg" >&2; exit 2; }
done

name="$cronjob-$(date +%s)-$RANDOM"
if [[ $# -gt 0 || -n "$near" ]]; then
  yaml=$(k create job "$name" --from="cronjob/$cronjob" --dry-run=client -o yaml)
  [[ $# -eq 0 ]] || yaml=$(k set env --local -f - "$@" -o yaml <<< "$yaml")
  if [ -n "$near" ]; then
    node=$(k get pod "$near" -o jsonpath='{.spec.nodeName}')
    [ -n "$node" ] || { echo "run-job.sh: cannot find the machine of $near" >&2; exit 1; }
    yaml=$(k patch --local -f - --type merge -o yaml \
      -p "{\"spec\":{\"template\":{\"spec\":{\"nodeSelector\":{\"kubernetes.io/hostname\":\"$node\"}}}}}" <<< "$yaml")
  fi
  k create -f - >/dev/null <<< "$yaml"
else
  k create job "$name" --from="cronjob/$cronjob" >/dev/null
fi
# The time limit is mutable on a running Job and counts from its start, so set it right after creating.
[ -z "$deadline" ] || k patch job "$name" --type merge -p "{\"spec\":{\"activeDeadlineSeconds\":$deadline}}" >/dev/null
echo "job/$name${deadline:+ (time limit $deadline s)}" >&2

# Wait for the pod to start (image already in the cluster), then follow its log to the end.
k wait --for=condition=Ready pod -l job-name="$name" --timeout=120s >/dev/null 2>&1 \
  || k wait --for=jsonpath='{.status.phase}'=Succeeded pod -l job-name="$name" --timeout=5s >/dev/null 2>&1 \
  || k wait --for=jsonpath='{.status.phase}'=Failed pod -l job-name="$name" --timeout=5s >/dev/null 2>&1 || true
k logs -f "job/$name" || true

# A log stream can end before the Job does (a dropped connection), so the verdict is the Job's own
# Complete or Failed condition, however long that takes; the time limit bounds it.
# A failed read (an API blip) is retried for a minute rather than reported as the Job failing.
misses=0
while :; do
  if ! conditions=$(k get job "$name" -o jsonpath='{range .status.conditions[?(@.status=="True")]}{.type}:{.reason} {end}'); then
    misses=$((misses + 1))
    [ "$misses" -lt 30 ] || { echo "job/$name: could not read its status for a minute" >&2; exit 1; }
    sleep 2
    continue
  fi
  misses=0
  case " $conditions" in
    *" Complete:"*|*" Failed:"*) break ;;
  esac
  sleep 2
done
if [[ " $conditions" == *" Failed:DeadlineExceeded"* ]]; then
  echo "job/$name: time limit reached${deadline:+ ($deadline s)}" >&2
  exit 1
fi
code=$(k get pods -l job-name="$name" -o jsonpath='{.items[0].status.containerStatuses[0].state.terminated.exitCode}' 2>/dev/null || true)
if [[ " $conditions" == *" Complete:"* ]]; then exit "${code:-0}"; fi
echo "job/$name failed${code:+ (exit $code)}" >&2
exit "${code:-1}"

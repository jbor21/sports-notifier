#!/usr/bin/env bash
# Run the simulator once, as a one-off Job stamped from the chart's suspended "simulator" CronJob.
# Optional KEY=VALUE args override its env, e.g. SIM_WAVES=1. Prints only the Job name on stdout.
set -euo pipefail

# Always the k3d cluster, whatever kubectl's current context is.
KUBE_CONTEXT=${KUBE_CONTEXT:-k3d-sports-notifier}
k() { kubectl --context "$KUBE_CONTEXT" "$@"; }

for arg in "$@"; do
  if [[ ! "$arg" =~ ^[A-Z_][A-Z0-9_]*= ]]; then
    echo "usage: simulate.sh [KEY=VALUE ...]" >&2
    exit 2
  fi
done

name="simulator-$(date +%s)-$RANDOM"

if [[ $# -gt 0 ]]; then
  k create job "$name" --from=cronjob/simulator --dry-run=client -o yaml \
    | k set env --local -f - "$@" -o yaml \
    | k create -f - >&2
else
  k create job "$name" --from=cronjob/simulator >&2
fi

echo "$name"

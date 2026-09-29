#!/usr/bin/env bash
# Run the simulator once, as a one-off Job stamped from the chart's suspended "simulator" CronJob.
# Optional KEY=VALUE args override its env, e.g. SIM_WAVES=1. Prints only the Job name on stdout.
set -euo pipefail

for arg in "$@"; do
  if [[ ! "$arg" =~ ^[A-Z_][A-Z0-9_]*= ]]; then
    echo "usage: simulate.sh [KEY=VALUE ...]" >&2
    exit 2
  fi
done

name="simulator-$(date +%s)-$RANDOM"

if [[ $# -gt 0 ]]; then
  kubectl create job "$name" --from=cronjob/simulator --dry-run=client -o yaml \
    | kubectl set env --local -f - "$@" -o yaml \
    | kubectl create -f - >&2
else
  kubectl create job "$name" --from=cronjob/simulator >&2
fi

echo "$name"

#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PID_DIR="$ROOT/pids"
if [[ ! -d "$PID_DIR" ]]; then
  echo "No pid directory — nothing to stop."
  exit 0
fi
for f in "$PID_DIR"/*.pid; do
  [[ -e "$f" ]] || continue
  pid=$(cat "$f")
  name=$(basename "$f" .pid)
  if kill -0 "$pid" 2>/dev/null; then
    kill "$pid" 2>/dev/null || true
    echo "stopped $name (pid $pid)"
  else
    echo "$name already gone"
  fi
  rm -f "$f"
done

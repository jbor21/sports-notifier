#!/usr/bin/env bash
# Starts the ESPN live data poller as a background process.
# Polls NBA, NFL, MLB, NHL scoreboards every 30s and forwards
# real game events to the Event Processing Service.
#
# Usage:
#   bash scripts/run-espn-poller.sh                     # default 30s interval
#   ESPN_POLL_INTERVAL_MS=15000 bash scripts/run-espn-poller.sh
#
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/code/target/espn-poller.jar"

if [[ ! -f "$JAR" ]]; then
  echo "espn-poller.jar not found — run ./quickstart.sh first."
  exit 1
fi

mkdir -p "$ROOT/logs" "$ROOT/pids"

PID_FILE="$ROOT/pids/espn-poller.pid"
if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
  echo "ESPN poller already running (pid $(cat "$PID_FILE"))"
  exit 0
fi

nohup java -jar "$JAR" \
  > "$ROOT/logs/espn-poller.log" 2>&1 &

echo $! > "$PID_FILE"
echo "ESPN poller started (pid $!) -> $ROOT/logs/espn-poller.log"
echo ""
echo "Tailing logs (Ctrl-C to stop tailing — poller keeps running):"
echo "──────────────────────────────────────────────────────────────"
sleep 1
tail -f "$ROOT/logs/espn-poller.log"

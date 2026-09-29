#!/usr/bin/env bash
# Starts every service as an independent JVM. Each process logs to logs/<svc>.log
# and writes its PID to pids/<svc>.pid so stop-all.sh can clean up.
set -euo pipefail

# ROOT is the project root (com3810)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CODE_DIR="$ROOT/code"
LOG_DIR="$ROOT/logs"
PID_DIR="$ROOT/pids"
TARGET="$CODE_DIR/target"

cd "$ROOT"
mkdir -p "$LOG_DIR" "$PID_DIR"

# --- Redis check ---
if ! redis-cli ping > /dev/null 2>&1; then
  echo "Redis not running. Start it with:  redis-server --daemonize yes"
  exit 1
fi
echo "Redis: OK"

# --- Build check ---
if [[ ! -f "$TARGET/gateway.jar" ]]; then
  echo "Jars not found in $TARGET. Please run './quickstart.sh' or 'mvn -f code/pom.xml package' first."
  exit 1
fi

JAR() { echo "$TARGET/$1.jar"; }

start_svc() {
  local name="$1"; shift
  local jar="$1"; shift
  if [[ -f "$PID_DIR/$name.pid" ]] && kill -0 "$(cat "$PID_DIR/$name.pid")" 2>/dev/null; then
    echo "$name already running (pid $(cat "$PID_DIR/$name.pid"))"
    return
  fi
  env "$@" nohup java -jar "$jar" > "$LOG_DIR/$name.log" 2>&1 &
  echo $! > "$PID_DIR/$name.pid"
  echo "started $name (pid $!) -> $LOG_DIR/$name.log"
}

start_svc user-pref "$(JAR user-pref)" USER_PREF_PORT=8081
start_svc game-state "$(JAR game-state)" GAME_STATE_PORT=8082
start_svc event-processing "$(JAR event-processing)" EVENT_PROCESSING_PORT=8083
start_svc notification "$(JAR notification)" NOTIFICATION_PORT=8087
start_svc worker-1 "$(JAR pubsub-worker)" PUBSUB_WORKER_PORT=8084 PUBSUB_CONSUMER_NAME=worker-1
start_svc worker-2 "$(JAR pubsub-worker)" PUBSUB_WORKER_PORT=8085 PUBSUB_CONSUMER_NAME=worker-2
start_svc gateway "$(JAR gateway)" GATEWAY_PORT=8080

echo ""
echo "All services started. Open http://127.0.0.1:8080 in your browser."
echo "To start the event simulator:  bash scripts/run-simulator.sh"
echo "To stop everything:            bash scripts/stop-all.sh"

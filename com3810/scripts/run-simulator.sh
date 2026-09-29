#!/usr/bin/env bash
set -euo pipefail
# ROOT is the project root (com3810)
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/code/target/simulator.jar"
if [[ ! -f "$JAR" ]]; then
  echo "simulator.jar missing — run ./quickstart.sh first."
  exit 1
fi
mkdir -p "$ROOT/logs" "$ROOT/pids"
nohup java -jar "$JAR" > "$ROOT/logs/simulator.log" 2>&1 &
echo $! > "$ROOT/pids/simulator.pid"
echo "simulator started (pid $!) -> $ROOT/logs/simulator.log"

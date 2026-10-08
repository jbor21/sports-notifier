#!/usr/bin/env bash
# Cuts one recorded game into a replay fixture for ReplayGoldenTest.
#
# Usage: scripts/cut-replay-fixture.sh <league> <espn event id>
#   e.g. scripts/cut-replay-fixture.sh nfl 401872971
#
# Every recording under $RECORDINGS_DIR/<league>/ (default ~/sportsnotifier-recordings) that has the
# game is trimmed to that game by trim-espn-fixture.py and written, in time order, to
# src/test/resources/espn/replay/<league>-<id>/0001.json, 0002.json, ... A board whose trimmed content
# is the same as the one before it (the recorder polled every 30 s; a game sits unchanged for hours
# before the start) is skipped. expected.txt is not touched: it is written by hand.
set -euo pipefail

if [ $# -ne 2 ]; then
  echo "usage: $0 <league> <espn event id>" >&2
  exit 2
fi
league=$(echo "$1" | tr '[:upper:]' '[:lower:]')
event="$2"
root=$(cd "$(dirname "$0")/.." && pwd)
src="${RECORDINGS_DIR:-$HOME/sportsnotifier-recordings}/$league"
out="$root/src/test/resources/espn/replay/$league-$event"

[ -d "$src" ] || { echo "no recordings in $src" >&2; exit 1; }
mkdir -p "$out"
rm -f "$out"/[0-9][0-9][0-9][0-9].json

tmp=$(mktemp)
trap 'rm -f "$tmp"' EXIT
n=0
last=""
for f in $(ls "$src" | grep '\.json\.gz$' | sort); do
  # Cheap filter first; the trim script then picks the event by its exact id.
  gzip -dc "$src/$f" | grep -q "\"$event\"" || continue
  python3 "$root/scripts/trim-espn-fixture.py" "$src/$f" "$event" "$tmp" 2>/dev/null || continue
  # The _note line names the recording file, so it differs every time, and the last play's text
  # changes without the position changing; neither is read by the parser. Compare the rest.
  body=$(grep -v -e '"_note"' -e '"text"' "$tmp")
  [ "$body" = "$last" ] && continue
  last="$body"
  n=$((n + 1))
  cp "$tmp" "$out/$(printf '%04d' "$n").json"
done
echo "$n boards written to ${out#$root/}"

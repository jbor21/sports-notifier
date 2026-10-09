#!/usr/bin/env python3
"""Cuts one game out of a recorded ESPN scoreboard and keeps only the fields the parser reads.

Usage: scripts/trim-espn-fixture.py <recording.json.gz> <espn event id> <out.json>

Recordings come from the recorder described in issue #3 (~/sportsnotifier-recordings/<league>/<UTC>.json.gz).
"""
import gzip, json, os, sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from espn_trim import trim_event  # noqa: E402

src, event_id, out = sys.argv[1], sys.argv[2], sys.argv[3]
with gzip.open(src) as f:
    board = json.load(f)
event = next(e for e in board["events"] if e["id"] == event_id)

fixture = {
    "events": [trim_event(event)],
    "_note": f"captured live: recording {os.path.basename(os.path.dirname(src))}/{os.path.basename(src)}, "
             f"trimmed to one game by scripts/trim-espn-fixture.py",
}
with open(out, "w") as f:
    json.dump(fixture, f, indent=1)
    f.write("\n")

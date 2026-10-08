#!/usr/bin/env python3
"""Cuts one game out of a recorded ESPN scoreboard and keeps only the fields the parser reads.

Usage: scripts/trim-espn-fixture.py <recording.json.gz> <espn event id> <out.json>

Recordings come from the recorder described in issue #3 (~/sportsnotifier-recordings/<league>/<UTC>.json.gz).
"""
import gzip, json, os, sys

src, event_id, out = sys.argv[1], sys.argv[2], sys.argv[3]
with gzip.open(src) as f:
    board = json.load(f)
event = next(e for e in board["events"] if e["id"] == event_id)
comp = event["competitions"][0]
status = comp["status"]

trimmed_comp = {
    "status": {
        "clock": status.get("clock"),
        "displayClock": status.get("displayClock"),
        "period": status.get("period"),
        "type": {k: status["type"].get(k) for k in
                 ("id", "name", "state", "completed", "description", "detail", "shortDetail")},
    },
    "competitors": [{
        "homeAway": c["homeAway"],
        "score": c.get("score"),
        "team": {k: c["team"].get(k) for k in ("abbreviation", "displayName", "logo")},
        "linescores": [{"value": l.get("value")} for l in c.get("linescores", [])],
    } for c in comp["competitors"]],
}
if "venue" in comp:
    trimmed_comp["venue"] = {"fullName": comp["venue"].get("fullName")}
if comp.get("broadcasts"):
    trimmed_comp["broadcasts"] = [{"names": b.get("names")} for b in comp["broadcasts"]]
if "situation" in comp:
    s = comp["situation"]
    trimmed_comp["situation"] = {"outs": s.get("outs"), "lastPlay": {"text": (s.get("lastPlay") or {}).get("text")}}

fixture = {
    "events": [{
        "id": event["id"],
        "date": event.get("date"),
        "shortName": event.get("shortName"),
        "season": {"type": (event.get("season") or {}).get("type")},
        "competitions": [trimmed_comp],
    }],
    "_note": f"captured live: recording {os.path.basename(os.path.dirname(src))}/{os.path.basename(src)}, "
             f"trimmed to one game by scripts/trim-espn-fixture.py",
}
with open(out, "w") as f:
    json.dump(fixture, f, indent=1)
    f.write("\n")

"""Keeps only the fields of an ESPN scoreboard event that EspnScoreFeed reads.

Shared by trim-espn-fixture.py (test fixtures) and pack-replay.py (replay sets).
"""


def trim_event(event):
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
    return {
        "id": event["id"],
        "date": event.get("date"),
        "shortName": event.get("shortName"),
        "season": {"type": (event.get("season") or {}).get("type")},
        "competitions": [trimmed_comp],
    }

#!/usr/bin/env python3
"""Writes src/main/resources/catalog/teams.json from ESPN's public teams lists.

Run by hand when a league adds, moves, or renames a team, then commit the output. The service never
calls ESPN for team names: a missing or blocked ESPN must not break following a team.

Team ids are "<league lower>-<abbreviation lower>", the same rule as feed/TeamIds.java, so the ids the
poller produces from a scoreboard match the ids in this file.
"""
import json
import pathlib
import urllib.request

HOST = "site.web.api.espn.com"
# Same honest user agent as values.yaml (espn.userAgent).
USER_AGENT = "sports-notifier/1.0 (+https://github.com/boboborenstein-collab/sports-notifier)"
LEAGUES = {
    "NFL": "football/nfl",
    "NBA": "basketball/nba",
    "MLB": "baseball/mlb",
    "NHL": "hockey/nhl",
}
OUT = pathlib.Path(__file__).resolve().parent.parent / "src/main/resources/catalog/teams.json"


def fetch(path):
    url = f"https://{HOST}/apis/site/v2/sports/{path}/teams?limit=100"
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.load(resp)


def main():
    teams = []
    for league, path in LEAGUES.items():
        data = fetch(path)
        for entry in data["sports"][0]["leagues"][0]["teams"]:
            t = entry["team"]
            abbr = t["abbreviation"]
            teams.append({
                "id": f"{league.lower()}-{abbr.lower()}",
                "league": league,
                "abbreviation": abbr,
                "displayName": t["displayName"],
                # "Red Sox", "Bears": what alert text calls the team.
                "shortName": t.get("shortDisplayName") or abbr,
            })
    teams.sort(key=lambda t: (t["league"], t["id"]))
    ids = [t["id"] for t in teams]
    if len(ids) != len(set(ids)):
        raise SystemExit("duplicate team id; check ESPN's abbreviations")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps({"teams": teams}, indent=2) + "\n")
    counts = {lg: sum(1 for t in teams if t["league"] == lg) for lg in LEAGUES}
    print(f"wrote {OUT} {counts}")


if __name__ == "__main__":
    main()

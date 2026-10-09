#!/usr/bin/env python3
"""Packs recorded ESPN games into a replay set for RecordedScoreFeed (Phase 4.1 spec, section 2.1).

Usage:
  scripts/pack-replay.py --list [--recordings DIR]
  scripts/pack-replay.py <set> <league>:<espnId>[@<offsetMinutes>] ... [--recordings DIR]

Writes src/main/resources/replay/<set>.jsonl.gz: one JSON line per kept snapshot, in time order,
  {"game": "nfl-401872971", "league": "NFL", "t": <ms>, "event": <trimmed ESPN event>}

Each game has its own timeline. It starts at its offset (minutes, from the command line) at the game's
first kept snapshot. A snapshot is kept when the trimmed event differs from the game's previous one,
and a gap longer than 60 s between kept snapshots is cut to 60 s: the recorder polled every 30 s, so a
longer gap is a pre-game wait, an intermission, or a recorder outage, and a replay should not sit
through it. Games recorded on different days can then overlap in one run.

A game must start before kickoff and run through its final: the checker primes on the pre-game
snapshot and waits for every game to be final.
"""
import gzip
import json
import os
import sys
from datetime import datetime, timezone

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from espn_trim import trim_event  # noqa: E402

MAX_GAP_MS = 60_000
LEAGUES = ("nfl", "nba", "mlb", "nhl")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


class PackError(Exception):
    pass


def parse_spec(spec):
    """'nfl:401872971@20' -> ('nfl', '401872971', 1200000)."""
    game, _, offset = spec.partition("@")
    league, _, espn_id = game.partition(":")
    if league.lower() not in LEAGUES or not espn_id:
        raise PackError(f"bad game spec {spec!r}: want <league>:<espnId>[@<minutes>]")
    return league.lower(), espn_id, int(offset or 0) * 60_000


def state(event):
    t = event["competitions"][0]["status"]["type"]
    return t.get("state"), bool(t.get("completed"))


def check(game, snaps):
    """Refuses a game whose recording does not start scheduled and end final."""
    if not snaps:
        raise PackError(f"{game}: not in the recordings")
    first, _ = state(snaps[0][1])
    _, done = state(snaps[-1][1])
    if first != "pre":
        raise PackError(f"{game}: recording starts '{first}', not before the game")
    if not done:
        raise PackError(f"{game}: recording does not reach the final")


def timeline(snaps, offset_ms):
    """[(recorded ms, raw event)] in time order -> [(t, trimmed event)]: unchanged dropped, gaps cut."""
    out = []
    last_json = None
    last_at = None
    t = offset_ms
    for at, raw in snaps:
        trimmed = trim_event(raw)
        as_json = json.dumps(trimmed, sort_keys=True)
        if as_json == last_json:
            continue
        if last_at is not None:
            t += min(at - last_at, MAX_GAP_MS)
        out.append((t, trimmed))
        last_json, last_at = as_json, at
    return out


def recording_time(name):
    """'20261002T192750Z.json.gz' -> epoch ms."""
    stamp = name.split(".")[0]
    return int(datetime.strptime(stamp, "%Y%m%dT%H%M%SZ").replace(tzinfo=timezone.utc).timestamp() * 1000)


def boards(recordings, league):
    """Yields (recorded ms, scoreboard) for one league, in time order; unreadable files skipped."""
    folder = os.path.join(recordings, league)
    if not os.path.isdir(folder):
        return
    for name in sorted(os.listdir(folder)):
        if not name.endswith(".json.gz"):
            continue
        try:
            with gzip.open(os.path.join(folder, name)) as f:
                yield recording_time(name), json.load(f)
        except (OSError, ValueError) as e:
            print(f"skipped {league}/{name}: {e}", file=sys.stderr)


def snapshots(recordings, league, ids=None):
    """{espnId: [(recorded ms, raw event)]} for every game (or only ids) of one league."""
    games = {}
    for at, board in boards(recordings, league):
        for event in board.get("events", []):
            if ids is None or event.get("id") in ids:
                games.setdefault(event["id"], []).append((at, event))
    return games


def stamp(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime("%m-%d %H:%M")


def list_games(recordings):
    for league in LEAGUES:
        for espn_id, snaps in snapshots(recordings, league).items():
            first, _ = state(snaps[0][1])
            last, done = state(snaps[-1][1])
            print(f"{league} {espn_id} {first} {last}{'/final' if done else ''} "
                  f"{stamp(snaps[0][0])} {stamp(snaps[-1][0])} {len(snaps)}")


def pack(set_name, specs, recordings):
    wanted = [parse_spec(s) for s in specs]
    lines = []
    for league in LEAGUES:
        mine = {espn_id: offset for lg, espn_id, offset in wanted if lg == league}
        if not mine:
            continue
        found = snapshots(recordings, league, set(mine))
        for espn_id, offset in mine.items():
            game = f"{league}-{espn_id}"
            snaps = found.get(espn_id, [])
            check(game, snaps)
            for t, event in timeline(snaps, offset):
                lines.append({"game": game, "league": league.upper(), "t": t, "event": event})
    lines.sort(key=lambda l: (l["t"], l["game"]))
    out = os.path.join(ROOT, "src", "main", "resources", "replay", f"{set_name}.jsonl.gz")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    # mtime=0 so the same recordings always pack to the same bytes.
    with open(out, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", mtime=0) as f:
        for line in lines:
            f.write((json.dumps(line, separators=(",", ":")) + "\n").encode())
    games = len(wanted)
    print(f"{out}: {games} games, {len(lines)} snapshots, {os.path.getsize(out) // 1024} KB")


def main(argv):
    recordings = os.path.expanduser(os.environ.get("RECORDINGS_DIR", "~/sportsnotifier-recordings"))
    args = []
    i = 0
    while i < len(argv):
        if argv[i] == "--recordings":
            recordings = argv[i + 1]
            i += 2
        else:
            args.append(argv[i])
            i += 1
    try:
        if args == ["--list"]:
            list_games(recordings)
        elif len(args) >= 2:
            pack(args[0], args[1:], recordings)
        else:
            print(__doc__, file=sys.stderr)
            return 2
    except PackError as e:
        print(f"pack-replay: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))

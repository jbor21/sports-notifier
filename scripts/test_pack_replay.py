"""Tests for pack-replay.py. Run: python3 -m unittest scripts/test_pack_replay.py"""
import importlib.util
import os
import unittest

_here = os.path.dirname(os.path.abspath(__file__))
_spec = importlib.util.spec_from_file_location("pack_replay", os.path.join(_here, "pack-replay.py"))
pack = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(pack)


def event(state, completed=False, home="0", away="0", clock="15:00"):
    """A raw ESPN event, as a scoreboard holds it (more fields than the trimmer keeps)."""
    return {
        "id": "401", "date": "2026-10-04T17:00Z", "shortName": "A @ B", "uid": "dropped",
        "season": {"type": 2, "year": 2026},
        "competitions": [{
            "status": {"clock": 900, "displayClock": clock, "period": 1,
                       "type": {"id": "1", "name": "STATUS", "state": state, "completed": completed,
                                "description": "", "detail": "", "shortDetail": ""}},
            "competitors": [
                {"homeAway": "home", "score": home, "team": {"abbreviation": "B", "displayName": "B", "logo": ""}},
                {"homeAway": "away", "score": away, "team": {"abbreviation": "A", "displayName": "A", "logo": ""}},
            ],
        }],
    }


class TimelineTest(unittest.TestCase):

    def test_dropsUnchangedSnapshots(self):
        snaps = [(0, event("pre")), (30_000, event("pre")), (60_000, event("in", home="7"))]
        out = pack.timeline(snaps, offset_ms=0)
        self.assertEqual([0, 60_000], [t for t, _ in out])

    def test_longGapsCompressedTo60s(self):
        snaps = [(0, event("pre")), (30_000, event("in")), (30_000 + 3 * 3600_000, event("in", home="1"))]
        out = pack.timeline(snaps, offset_ms=0)
        self.assertEqual([0, 30_000, 90_000], [t for t, _ in out])

    def test_offsetAddedToTimeline(self):
        snaps = [(0, event("pre")), (30_000, event("in"))]
        out = pack.timeline(snaps, offset_ms=20 * 60_000)
        self.assertEqual([1_200_000, 1_230_000], [t for t, _ in out])

    def test_eventsAreTrimmed(self):
        out = pack.timeline([(0, event("pre"))], offset_ms=0)
        self.assertNotIn("uid", out[0][1])
        self.assertEqual("pre", out[0][1]["competitions"][0]["status"]["type"]["state"])


class CheckTest(unittest.TestCase):

    def test_refusesGameNotStartingScheduled(self):
        with self.assertRaises(pack.PackError) as e:
            pack.check("nfl-401", [(0, event("in")), (1, event("post", completed=True))])
        self.assertIn("nfl-401", str(e.exception))

    def test_refusesGameNotEndingFinal(self):
        with self.assertRaises(pack.PackError):
            pack.check("nfl-401", [(0, event("pre")), (1, event("in"))])

    def test_acceptsScheduledToFinal(self):
        pack.check("nfl-401", [(0, event("pre")), (1, event("post", completed=True))])


class SpecTest(unittest.TestCase):

    def test_parsesLeagueIdAndOffset(self):
        self.assertEqual(("nfl", "401872971", 20 * 60_000), pack.parse_spec("nfl:401872971@20"))
        self.assertEqual(("nhl", "401891803", 0), pack.parse_spec("NHL:401891803"))


if __name__ == "__main__":
    unittest.main()

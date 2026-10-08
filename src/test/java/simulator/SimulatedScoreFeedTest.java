package simulator;

import catalog.Teams;
import feed.Seq;
import feed.TeamIds;
import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The simulator's scripted games and the feed that plays them back (spec 1.4). */
class SimulatedScoreFeedTest {

    private static final long STEP = 2000;
    private static final long T0 = 1_000_000L;

    private static SimulatedScoreFeed feed(long runStart, List<GameScript> wave, AtomicLong clock) {
        return new SimulatedScoreFeed(runStart, wave, clock::get, STEP);
    }

    /** Halfway through step k: past any start jitter (under half a step), short of step k + 1. */
    private static long middleOfStep(int k) {
        return T0 + k * STEP + STEP / 2;
    }

    @Test
    void twoRunsHaveDifferentGameIds() throws Exception {
        List<GameScript> wave = List.of(GameScript.nba("LAL", "BOS"), GameScript.mlb("BOS", "HOU"));
        AtomicLong clock = new AtomicLong(T0);
        SimulatedScoreFeed first = feed(1_760_000_000L, wave, clock);
        SimulatedScoreFeed second = feed(1_760_000_100L, wave, clock);

        assertEquals(List.of("nba-sim1760000000-1", "mlb-sim1760000000-2"), first.gameIds());
        assertEquals("mlb-sim1760000000-2", first.fetch("MLB").get(0).gameId());
        Set<String> ids = new HashSet<>(first.gameIds());
        ids.retainAll(second.gameIds());
        assertTrue(ids.isEmpty(), "a rerun reuses no game id: " + ids);
    }

    @Test
    void slotsRunAcrossWavesSoOneRunNeverRepeatsAnId() {
        Set<String> ids = new HashSet<>();
        int games = 0;
        for (List<GameScript> wave : EventSimulatorMain.waves()) {
            ids.addAll(new SimulatedScoreFeed(1_760_000_000L, wave, () -> T0, STEP).gameIds());
            games += wave.size();
        }
        assertEquals(games, ids.size());
        // Wave 1's Astros game, the one the smoke test follows.
        assertTrue(ids.contains("mlb-sim1760000000-5"), ids.toString());
    }

    @Test
    void everySimulatedTeamIsInTeamsJson() {
        Teams teams = Teams.load();
        for (List<GameScript> wave : EventSimulatorMain.waves()) {
            for (GameScript g : wave) {
                for (String abbr : List.of(g.home(), g.away())) {
                    String id = TeamIds.of(g.league(), abbr);
                    assertTrue(teams.find(id).isPresent(), id + " is not in teams.json, so nobody could follow it");
                }
            }
        }
    }

    @Test
    void nflScriptHasTouchdownThenExtraPointNextStep() {
        List<GameScript.Step> s = GameScript.nfl("KC", "SF").steps();
        int td = -1;
        for (int i = 1; i < s.size(); i++) {
            if (s.get(i).homeScore() - s.get(i - 1).homeScore() == 6) { td = i; break; }
        }
        assertTrue(td > 0, "a KC touchdown on its own");
        GameScript.Step touchdown = s.get(td), extraPoint = s.get(td + 1);
        assertEquals(1, extraPoint.homeScore() - touchdown.homeScore(), "the extra point lands one step later");
        assertEquals(touchdown.awayScore(), extraPoint.awayScore());
        assertEquals(touchdown.period(), extraPoint.period());
        assertEquals(touchdown.clockTenths(), extraPoint.clockTenths(), "the clock is stopped for the try");
        assertEquals("IN_PROGRESS", extraPoint.status());
    }

    @Test
    void nflScriptHasEveryWaveOneMoment() {
        List<GameScript.Step> s = GameScript.nfl("KC", "SF").steps();
        boolean fieldGoalSf = false, touchdownAndTwo = false, halftime = false, endOfQ3 = false, sfTakesLeadInQ4 = false;
        int lateAndClose = 0, mostLateAndClose = 0;
        for (int i = 1; i < s.size(); i++) {
            GameScript.Step prev = s.get(i - 1), cur = s.get(i);
            int dh = cur.homeScore() - prev.homeScore(), da = cur.awayScore() - prev.awayScore();
            fieldGoalSf |= da == 3 && dh == 0;
            touchdownAndTwo |= dh == 8 && da == 0;
            halftime |= cur.stage() == Stage.HALFTIME && cur.period() == 2;
            endOfQ3 |= cur.stage() == Stage.END_PERIOD && cur.period() == 3;
            sfTakesLeadInQ4 |= cur.period() == 4 && cur.phase() == Phase.LIVE
                && prev.homeScore() >= prev.awayScore() && cur.awayScore() > cur.homeScore();
            boolean late = cur.phase() == Phase.LIVE && cur.period() == 4 && cur.clockTenths() <= 3000
                && Math.abs(cur.homeScore() - cur.awayScore()) <= 8;
            lateAndClose = late ? lateAndClose + 1 : 0;
            mostLateAndClose = Math.max(mostLateAndClose, lateAndClose);
        }
        assertTrue(fieldGoalSf, "SF field goal");
        assertTrue(touchdownAndTwo, "KC touchdown with a 2-point try in one step");
        assertTrue(halftime, "halftime");
        assertTrue(endOfQ3, "end of Q3");
        assertTrue(sfTakesLeadInQ4, "SF lead change in Q4");
        assertTrue(mostLateAndClose >= 2, "Q4 under 5:00 within 8 on two steps in a row");
        assertEquals(Phase.FINAL, s.get(s.size() - 1).phase());
    }

    @Test
    void mlbScriptHasAnAstrosRunInTheSecondAndRedSoxRunsInTheFifth() {
        List<GameScript.Step> s = GameScript.mlb("BOS", "HOU").steps();
        boolean awayRunIn2nd = false, homeRunIn5th = false;
        Set<Integer> innings = new HashSet<>();
        Set<Half> halves = new HashSet<>();
        for (int i = 1; i < s.size(); i++) {
            GameScript.Step prev = s.get(i - 1), cur = s.get(i);
            awayRunIn2nd |= cur.period() == 2 && cur.awayScore() > prev.awayScore();
            homeRunIn5th |= cur.period() == 5 && cur.homeScore() > prev.homeScore();
            if (cur.phase() == Phase.LIVE) {
                innings.add(cur.period());
                halves.add(cur.half());
            }
        }
        assertTrue(awayRunIn2nd);
        assertTrue(homeRunIn5th);
        assertEquals(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9), innings);
        assertEquals(Set.of(Half.values()), halves);
    }

    @Test
    void nhlScriptHasAOneGoalThirdUnderFiveMinutes() {
        List<GameScript.Step> s = GameScript.nhl("TOR", "MTL").steps();
        int run = 0, most = 0;
        boolean homeGoal = false, awayGoal = false, end1 = false, end2 = false;
        for (int i = 1; i < s.size(); i++) {
            GameScript.Step prev = s.get(i - 1), cur = s.get(i);
            homeGoal |= cur.homeScore() > prev.homeScore();
            awayGoal |= cur.awayScore() > prev.awayScore();
            end1 |= cur.stage() == Stage.END_PERIOD && cur.period() == 1;
            end2 |= cur.stage() == Stage.END_PERIOD && cur.period() == 2;
            boolean late = cur.phase() == Phase.LIVE && cur.period() == 3 && cur.clockTenths() <= 3000
                && Math.abs(cur.homeScore() - cur.awayScore()) == 1;
            run = late ? run + 1 : 0;
            most = Math.max(most, run);
        }
        assertTrue(homeGoal && awayGoal && end1 && end2);
        assertTrue(most >= 2);
    }

    @Test
    void everyScriptMovesForwardOnEveryStep() {
        for (GameScript g : List.of(GameScript.nfl("KC", "SF"), GameScript.mlb("BOS", "HOU"),
                GameScript.nhl("TOR", "MTL"), GameScript.nba("LAL", "BOS"))) {
            List<GameScript.Step> s = g.steps();
            assertEquals(Phase.PRE, s.get(0).phase(), g.league());
            assertEquals("SCHEDULED", s.get(0).status(), g.league());
            GameScript.Step last = s.get(s.size() - 1);
            assertEquals("FINAL", last.status(), g.league());
            // The poller holds a tied final outside the NFL as ESPN mid-correction, so the run would never end.
            assertNotEquals(last.homeScore(), last.awayScore(), g.league());
            assertTrue(s.size() >= 35 && s.size() <= 50, g.league() + " has " + s.size() + " steps");
            long prevSeq = -1;
            for (int i = 0; i < s.size(); i++) {
                long seq = Seq.of(g.snapshot("x", i, 0));
                // Every step is a play: the poller sends on any seq change, and the script refuses a lower one.
                assertTrue(seq > prevSeq, g.league() + " step " + i + " does not move the game forward");
                prevSeq = seq;
            }
        }
    }

    @Test
    void stepsAdvanceWithTheClock() throws Exception {
        AtomicLong clock = new AtomicLong(T0);
        GameScript mlb = GameScript.mlb("BOS", "HOU");
        SimulatedScoreFeed f = feed(1_760_000_000L, List.of(mlb), clock);

        GameSnapshot pre = f.fetch("MLB").get(0);
        assertEquals(Phase.PRE, pre.phase());
        assertEquals("SCHEDULED", pre.status());

        for (int k : new int[] {1, 2, 7, 20}) {
            clock.set(middleOfStep(k));
            GameSnapshot snap = f.fetch("MLB").get(0);
            GameSnapshot expected = mlb.snapshot(snap.gameId(), k, snap.startTimeMillis());
            assertEquals(expected, snap, "step " + k);
        }
        assertTrue(f.fetch("NFL").isEmpty(), "only the asked league's games");
    }

    @Test
    void doneAfterLastStep() throws Exception {
        AtomicLong clock = new AtomicLong(T0);
        GameScript nhl = GameScript.nhl("TOR", "MTL"), mlb = GameScript.mlb("BOS", "HOU");
        SimulatedScoreFeed f = feed(1_760_000_000L, List.of(nhl, mlb), clock);
        int lastNhl = nhl.steps().size() - 1, lastMlb = mlb.steps().size() - 1;
        assertNotEquals(lastNhl, lastMlb, "the test needs games of different lengths");

        assertFalse(f.done());
        clock.set(middleOfStep(Math.min(lastNhl, lastMlb)));
        assertFalse(f.done(), "the longer game is still going");
        clock.set(middleOfStep(Math.max(lastNhl, lastMlb)));
        assertTrue(f.done());
        assertEquals("FINAL", f.fetch("MLB").get(0).status());

        clock.set(middleOfStep(500));
        assertEquals("FINAL", f.fetch("NHL").get(0).status(), "a finished game holds its final");
        assertEquals(nhl.snapshot(f.gameIds().get(0), lastNhl, f.fetch("NHL").get(0).startTimeMillis()),
            f.fetch("NHL").get(0));
    }

    @Test
    void lineScoreSumsToScore() {
        GameScript nfl = GameScript.nfl("KC", "BUF");
        for (int i = 0; i < nfl.steps().size(); i++) {
            model.GameSnapshot snap = nfl.snapshot("nfl-sim1-1", i, T0);
            assertEquals(snap.homeScore(), snap.lineScore().stream().mapToInt(model.LinePeriod::home).sum(), "step " + i);
            assertEquals(snap.awayScore(), snap.lineScore().stream().mapToInt(model.LinePeriod::away).sum(), "step " + i);
        }
        model.GameSnapshot last = nfl.snapshot("nfl-sim1-1", nfl.steps().size() - 1, T0);
        assertTrue(last.lineScore().size() >= 4, "every quarter gets a column: " + last.lineScore());
    }
}

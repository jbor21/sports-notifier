package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DetectorsTest {

    /** Emits a fixed list of alerts on every play, and a final when the play is final. */
    private static Detector always(Alert... alerts) {
        return new Detector() {
            @Override
            public void detect(GameMemory before, Play now, GameMemory after, List<Alert> out) {
                out.addAll(List.of(alerts));
            }

            @Override
            public Alert finalAlert(GameMemory before, Play now, GameMemory after) {
                return now.isFinal() ? alert("g:FINAL", "FINAL", 0, null) : null;
            }
        };
    }

    private static Alert alert(String id, String type, int priority, Side side) {
        return new Alert(id, type, List.of(type), priority, side, type);
    }

    @Test
    void nullMemoryIsBaselineWithNoAlerts() {
        Detectors d = new Detectors(List.of(always(alert("g:GOAL", "GOAL", Alert.P_SCORE, Side.HOME))));
        Play p = PlayBuilder.nhl("g").period(2).clock(1200).score(1, 0).build();
        Detectors.Result r = d.run(null, p);
        assertTrue(r.moment().isEmpty());
        assertNull(r.finalAlert());
        assertEquals(2, r.after().period);
        assertEquals(1, r.after().homeScore);
    }

    @Test
    void momentIsSortedByOrder() {
        Alert period = alert("g:END_OF_PERIOD:1", "END_OF_PERIOD", Alert.P_PERIOD, null);
        Alert awayGoal = alert("g:SCORE:away:1", "GOAL", Alert.P_SCORE, Side.AWAY);
        Alert lead = alert("g:LEAD_CHANGE:1", "LEAD_CHANGE", Alert.P_LEAD, Side.AWAY);
        Alert homeGoal = alert("g:SCORE:home:1", "GOAL", Alert.P_SCORE, Side.HOME);
        Alert overtime = alert("g:OVERTIME:4", "OVERTIME", Alert.P_OVERTIME, null);
        Detectors d = new Detectors(List.of(always(period, awayGoal, lead, homeGoal, overtime)));
        Play p = PlayBuilder.nhl("g").period(2).build();
        Detectors.Result r = d.run(new GameMemory(), p);
        assertEquals(List.of(overtime, homeGoal, awayGoal, lead, period), r.moment());
    }

    @Test
    void finalIsSeparateFromTheMoment() {
        Alert goal = alert("g:SCORE:home:1", "GOAL", Alert.P_SCORE, Side.HOME);
        Detectors d = new Detectors(List.of(always(goal)));
        Detectors.Result r = d.run(new GameMemory(), PlayBuilder.nhl("g").fin().period(4).build());
        assertEquals(List.of(goal), r.moment());
        assertEquals("g:FINAL", r.finalAlert().id());
    }

    @Test
    void afterTakesThePlaysPosition() {
        Detectors d = new Detectors(List.of());
        GameMemory before = new GameMemory();
        Detectors.Result r = d.run(before, PlayBuilder.nba("g").period(4).clock(2000).score(90, 88).build());
        assertEquals(4, r.after().period);
        assertEquals(2000, r.after().clockTenths);
        assertTrue(r.after().wasLate, "Q4 with 3:20 left is late in the NBA");
        assertEquals(0, before.period, "before is not changed");
    }

    @Test
    void baselineMarksPassedFlags() {
        PlayBuilder q3 = PlayBuilder.nfl("g").period(3).clock(500).score(14, 10);
        Sequence s = Sequence.standard().play(q3);
        GameMemory m = s.memory();
        assertTrue(m.fired("GAME_START"));
        assertTrue(m.fired("END_OF_PERIOD:1"));
        assertTrue(m.fired("HALFTIME"));
        assertFalse(m.fired("END_OF_PERIOD:3"));
        assertEquals(List.of(), s.ids());
        s.play(q3.next().stage("END_PERIOD").clock(0));
        assertEquals(List.of("g:END_OF_PERIOD:3"), s.ids());
    }

    @Test
    void catchingUpMidGameIsQuiet() {
        // Final review, Minor 6: last seen before kickoff, next seen in Q3 (poller asleep). Nothing that
        // happened in between is news now; the counters carry on, so later ids stay unique.
        PlayBuilder pre = PlayBuilder.nfl("g").pre();
        PlayBuilder delayed = pre.next().status("DELAYED").stage("DELAYED");
        PlayBuilder q3 = PlayBuilder.nfl("g").period(3).clock(5000).score(24, 10).version(3);
        Sequence s = Sequence.standard().play(pre, delayed, q3);
        assertEquals(List.of("g:DELAYED:1"), s.ids());
        s.play(q3.next().clock(4000).score(24, 13), q3.next().next().status("DELAYED").stage("DELAYED"));
        assertEquals(List.of("g:DELAYED:1", "g:SCORE:away:1", "g:DELAYED:2"), s.ids());
    }

    @Test
    void firstPeriodStartWithAScoreStillAlerts() {
        PlayBuilder pre = PlayBuilder.mlb("g").pre();
        Sequence s = Sequence.standard().play(pre, pre.next().live().period(1).half("BOTTOM").score(1, 0));
        assertEquals(List.of("GAME_START", "RUN_SCORED"), s.alerts().stream().map(Alert::type).sorted().toList());
    }
}

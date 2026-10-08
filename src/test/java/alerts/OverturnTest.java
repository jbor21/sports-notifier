package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A score that goes down (user, 2026-10-08). The recordings show two kinds: a real overturn drops at a
 * stopped clock (the review) and stays down while play goes on (nhl-401891803: 770, 770, 748, 709 s);
 * an ESPN glitch drops while the clock runs and comes back within seconds (nhl-401892443: 316 → 284 →
 * 257 s, up to 27 s). So a drop counts once it has held for 60 s of game clock (MLB: two more plays), a new period,
 * or the final; one that comes back before that sends nothing.
 */
class OverturnTest {

    private static Sequence seq() {
        return Sequence.of(new ScoreDetector(LABELS));
    }

    /** Rangers (away) score at 12:50 of the 1st; the goal is reviewed at the stopped clock. */
    private static final PlayBuilder NHL = PlayBuilder.nhl("g").period(1).clock(7700).score(0, 1);

    @Test
    void realOverturnAfterSixtySecondsOfClock() {
        Sequence s = seq().play(NHL, NHL.next().score(0, 2), NHL.next().next().score(0, 1),
            NHL.next().next().next().clock(7480).score(0, 1));
        assertEquals(List.of("g:SCORE:away:1"), s.ids(), "22 s of clock: not yet believed");
        s.play(NHL.next().next().next().next().clock(7090).score(0, 1));
        assertEquals(List.of("g:SCORE:away:1", "g:SCORE:away:2"), s.ids());
        Alert o = s.alerts().get(1);
        assertEquals("GOAL_OVERTURNED", o.type());
        assertEquals(List.of("GOAL"), o.switches());
        assertEquals(Alert.P_SCORE, o.priority());
        assertEquals(Side.AWAY, o.side());
        assertEquals("Rangers goal overturned. Rangers 1, Bruins 0 · 1st 11:49", o.text());
    }

    @Test
    void glitchThatComesBackSendsNothing() {
        // nhl-401892443: 1-1 at 5:16, 1-0 at 4:44, 1-1 again at 4:17. No overturn, and no second goal.
        PlayBuilder tied = PlayBuilder.nhl("g").period(2).clock(3160).score(1, 1);
        Sequence s = seq().play(tied, tied.next().clock(2840).score(1, 0), tied.next().next().clock(2570).score(1, 1));
        assertEquals(List.of(), s.ids());
    }

    @Test
    void scoreOverturnedThenScoredAgain() {
        // The user's test, updated: the overturn takes the next counter value, so the real goal after it
        // can't repeat an id dedup would drop.
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(12000).score(2, 0);
        Sequence s = seq().play(p, p.next().score(3, 0), p.next().next().score(2, 0),
            p.next().next().next().clock(11400).score(2, 0), p.next().next().next().next().clock(11000).score(3, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:home:2", "g:SCORE:home:3"), s.ids());
        assertEquals(List.of("GOAL", "GOAL_OVERTURNED", "GOAL"), s.alerts().stream().map(Alert::type).toList());
    }

    @Test
    void newPeriodConfirms() {
        PlayBuilder p = PlayBuilder.nhl("g").period(1).clock(50).score(1, 0);
        Sequence s = seq().play(p, p.next().clock(40).score(0, 0), p.next().next().period(2).clock(12000).score(0, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        assertEquals("GOAL_OVERTURNED", s.alerts().get(0).type());
    }

    @Test
    void finalConfirms() {
        PlayBuilder p = PlayBuilder.nhl("g").period(3).clock(100).score(4, 2);
        Sequence s = seq().play(p, p.next().fin().clock(0).score(3, 2));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        assertEquals("Bruins goal overturned. Bruins 3, Rangers 2", s.texts().get(0));
    }

    @Test
    void mlbAfterTwoPlays() {
        PlayBuilder p = PlayBuilder.mlb("g").period(4).half("BOTTOM").score(3, 1);
        Sequence s = seq().play(p, p.next().score(2, 1), p.next().next().score(2, 1));
        assertEquals(List.of(), s.ids());
        s.play(p.next().next().next().half("END").score(2, 1));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        Alert o = s.alerts().get(0);
        assertEquals("RUN_OVERTURNED", o.type());
        assertEquals(List.of("RUN_SCORED"), o.switches());
        assertEquals("Cubs run overturned. Cubs 2, Cardinals 1 · End 4th", o.text());
    }

    @Test
    void nflTouchdownOverturnedDropsTheTry() {
        PlayBuilder p = PlayBuilder.nfl("g").period(2).clock(6000);
        Sequence s = seq().play(p, p.next().clock(5000).score(6, 0));
        for (int clock = 5000; clock >= 4800; clock -= 100) s.play(p.next().clock(clock).score(0, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids(), "no 'try no good' while the drop is unconfirmed");
        s.play(p.next().clock(4400).score(0, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:home:2"), s.ids());
        assertEquals("TOUCHDOWN_OVERTURNED", s.alerts().get(1).type());
        assertEquals(List.of("TOUCHDOWN"), s.alerts().get(1).switches());
        assertEquals("Bears touchdown overturned. Raiders 0, Bears 0 · Q2 7:20", s.texts().get(1));
        assertNull(s.memory().pending, "the try went with the touchdown");
    }

    @Test
    void nflFieldGoalOverturned() {
        PlayBuilder p = PlayBuilder.nfl("g").period(3).clock(6000).score(10, 3);
        Sequence s = seq().play(p, p.next().clock(5000).score(10, 0), p.next().next().clock(4400).score(10, 0));
        assertEquals("FIELD_GOAL_OVERTURNED", s.alerts().get(0).type());
        assertEquals(List.of("FIELD_GOAL"), s.alerts().get(0).switches());
        assertEquals("Raiders field goal overturned. Bears 10, Raiders 0 · Q3 7:20", s.texts().get(0));
    }

    @Test
    void nflOtherCorrectionIsGeneric() {
        PlayBuilder p = PlayBuilder.nfl("g").period(3).clock(6000).score(10, 3);
        Sequence s = seq().play(p, p.next().clock(5000).score(9, 3), p.next().next().clock(4400).score(9, 3));
        Alert o = s.alerts().get(0);
        assertEquals("SCORE_OVERTURNED", o.type());
        assertEquals(List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY"), o.switches());
        assertEquals("Bears score corrected. Bears 9, Raiders 3 · Q3 7:20", o.text());
    }

    @Test
    void nbaNoOverturnAlert() {
        PlayBuilder p = PlayBuilder.nba("g").period(2).clock(3000).score(50, 48);
        assertEquals(List.of(), seq().play(p, p.next().clock(2500).score(48, 48), p.next().next().clock(2000).score(48, 48)).ids());
    }

    @Test
    void missingClockWaitsForThePeriod() {
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(-1).score(1, 0);
        Sequence s = seq().play(p, p.next().score(0, 0), p.next().next().score(0, 0), p.next().next().next().score(0, 0));
        assertEquals(List.of(), s.ids());
        s.play(p.next().next().next().next().period(3).score(0, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
    }

    @Test
    void dropHeldFiftySecondsIsStillNotBelieved() {
        // The glitches in the recordings lasted up to 27 s; 60 s leaves room (user, 2026-10-08).
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(6000).score(2, 1);
        Sequence s = seq().play(p, p.next().score(1, 1), p.next().next().clock(5500).score(1, 1), p.next().next().next().clock(5000).score(2, 1));
        assertEquals(List.of(), s.ids());
    }

    @Test
    void touchdownOverturnedThenFieldGoal() {
        // Final review, Important 1: a goal-line review, then a field goal on the next snap, inside the 60 s.
        PlayBuilder p = PlayBuilder.nfl("g").period(2).clock(6000).score(7, 7);
        Sequence s = seq().play(p, p.next().clock(5000).score(13, 7), p.next().clock(5000).score(7, 7),
            p.next().clock(4800).score(10, 7));
        assertEquals(List.of("TOUCHDOWN", "TOUCHDOWN_OVERTURNED", "FIELD_GOAL"), s.alerts().stream().map(Alert::type).toList());
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:home:2", "g:SCORE:home:3"), s.ids());
        assertEquals("Field goal Bears. Bears 10, Raiders 7 · Q2 8:00", s.texts().get(2));
        assertNull(s.memory().pending, "the touchdown's try went with it");
    }

    @Test
    void goalOverturnedThenAnotherGoalAboveTheLow() {
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(9000).score(1, 0);
        Sequence s = seq().play(p, p.next().score(3, 0), p.next().score(1, 0), p.next().clock(8800).score(2, 0));
        assertEquals(List.of("GOAL", "GOAL_OVERTURNED", "GOAL"), s.alerts().stream().map(Alert::type).toList());
        assertEquals("Bruins goals overturned. Bruins 1, Rangers 0 · 2nd 14:40", s.texts().get(1));
    }

    @Test
    void tryGoodAndOtherTeamScoresInOnePlay() {
        // Final review, Minor 4: not "Try no good" and "Extra point good after all" in one push.
        PlayBuilder p = PlayBuilder.nfl("g").period(2).clock(6000);
        Sequence s = seq().play(p, p.next().clock(5000).score(6, 0), p.next().clock(4000).score(7, 3));
        assertEquals(List.of("TOUCHDOWN", "FIELD_GOAL", "TRY_RESULT"), s.alerts().stream().map(Alert::type).toList());
        assertTrue(s.texts().get(2).startsWith("Extra point good."), s.texts().toString());
    }
}

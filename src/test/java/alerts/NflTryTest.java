package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

/** The NFL touchdown, its try, and the other NFL scores (spec 3.4). Clock 5000 tenths is 8:20. */
class NflTryTest {

    private static final PlayBuilder BASE = PlayBuilder.nfl("g").period(2).clock(6000);

    private static Sequence seq() {
        return Sequence.of(new ScoreDetector(LABELS));
    }

    /** BASE, then a home touchdown at 8:20. */
    private static Sequence touchdown() {
        return seq().play(BASE, BASE.next().clock(5000).score(6, 0));
    }

    private static PlayBuilder after(int clock, int home, int away) {
        return BASE.next().next().clock(clock).score(home, away);
    }

    @Test
    void touchdownThenExtraPoint() {
        Sequence s = touchdown().play(after(5000, 7, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:TRY_RESULT:home:1"), s.ids());
        assertEquals(List.of("Touchdown Bears! Extra point pending. Bears 6, Raiders 0 · Q2 8:20",
            "Extra point good. Bears 7, Raiders 0 · Q2 8:20"), s.texts());
        assertEquals("TOUCHDOWN", s.alerts().get(0).type());
        Alert t = s.alerts().get(1);
        assertEquals("TRY_RESULT", t.type());
        assertEquals(Alert.P_TRY, t.priority());
        assertNull(s.memory().pending);
    }

    @Test
    void touchdownThenTwoPointIsNotSafety() {
        Sequence s = touchdown().play(after(5000, 8, 0));
        assertEquals(List.of("TOUCHDOWN", "TRY_RESULT"), s.alerts().stream().map(Alert::type).toList());
        assertEquals("2-point conversion good. Bears 8, Raiders 0 · Q2 8:20", s.texts().get(1));
    }

    @Test
    void touchdownAndExtraPointInOnePlay() {
        Sequence s = seq().play(BASE, BASE.next().clock(5000).score(7, 0), after(4000, 7, 0), after(3000, 7, 0));
        assertEquals(List.of("Touchdown Bears, extra point good. Bears 7, Raiders 0 · Q2 8:20"), s.texts());
        assertNull(s.memory().pending);
    }

    @Test
    void touchdownAndTwoPointInOnePlay() {
        Sequence s = seq().play(BASE, BASE.next().clock(5000).score(8, 0));
        assertEquals(List.of("Touchdown Bears, 2-point conversion good. Bears 8, Raiders 0 · Q2 8:20"), s.texts());
    }

    @Test
    void clockBelowOnePlayStillPending() {
        Sequence s = touchdown().play(after(4900, 6, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        assertEquals(1, s.memory().pending.playsBelow());
    }

    @Test
    void clockBelowTwoPlaysNoGood() {
        Sequence s = touchdown().play(after(4900, 6, 0), after(4800, 6, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:TRY_RESULT:home:1"), s.ids());
        assertEquals("Try no good. Bears 6, Raiders 0 · Q2 8:00", s.texts().get(1));
        assertNull(s.memory().pending);
    }

    @Test
    void periodEndNoGood() {
        PlayBuilder late = PlayBuilder.nfl("g").period(2).clock(100);
        Sequence s = seq().play(late, late.next().clock(50).score(6, 0), late.next().next().stage("HALFTIME").clock(0).score(6, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:TRY_RESULT:home:1"), s.ids());
        assertTrue(s.texts().get(1).startsWith("Try no good."));
    }

    @Test
    void lateExtraPointAfterNoGood() {
        Sequence s = touchdown().play(after(4900, 6, 0), after(4800, 6, 0), after(4700, 7, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:TRY_RESULT:home:1", "g:TRY_RESULT:home:1:late"), s.ids());
        assertEquals("Extra point good after all. Bears 7, Raiders 0 · Q2 7:50", s.texts().get(2));
    }

    @Test
    void otherTeamScoresDuringTry() {
        Sequence s = touchdown().play(after(4000, 6, 3));
        // The moment is in headline order: the field goal (a score) before the try result.
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:away:1", "g:TRY_RESULT:home:1"), s.ids());
        assertEquals("FIELD_GOAL", s.alerts().get(1).type());
        assertTrue(s.texts().get(2).startsWith("Try no good."));
    }

    @Test
    void defensiveTwoIsGenericNotSafety() {
        Sequence s = touchdown().play(after(5000, 6, 2));
        assertEquals(List.of("TOUCHDOWN", "SCORE", "TRY_RESULT"), s.alerts().stream().map(Alert::type).toList());
        Alert generic = s.alerts().get(1);
        assertEquals(List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY"), generic.switches());
        assertEquals("Raiders score 2. Bears 6, Raiders 2 · Q2 8:20", generic.text());
    }

    @Test
    void safetyWithoutTry() {
        Sequence s = seq().play(BASE, after(5000, 0, 2));
        assertEquals("SAFETY", s.alerts().get(0).type());
        assertEquals("Safety! Raiders score 2. Raiders 2, Bears 0 · Q2 8:20", s.texts().get(0));
    }

    @Test
    void fieldGoal() {
        Sequence s = seq().play(BASE, after(5000, 3, 0));
        assertEquals("FIELD_GOAL", s.alerts().get(0).type());
        assertEquals("Field goal Bears. Bears 3, Raiders 0 · Q2 8:20", s.texts().get(0));
    }

    @Test
    void oddJumpIsGeneric() {
        Sequence s = seq().play(BASE, after(5000, 9, 0));
        Alert a = s.alerts().get(0);
        assertEquals("SCORE", a.type());
        assertEquals(List.of("TOUCHDOWN", "FIELD_GOAL", "SAFETY"), a.switches());
        assertEquals("Bears score 9. Bears 9, Raiders 0 · Q2 8:20", a.text());
    }

    @Test
    void missingClockNeverFailsATry() {
        Sequence s = touchdown().play(after(-1, 6, 0), after(-1, 6, 0), after(-1, 6, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        assertNotNull(s.memory().pending);
    }

    @Test
    void touchdownThatEndsTheGameHasNoTry() {
        // An overtime touchdown ends the game: there is no try, so no "pending" and no "no good".
        PlayBuilder ot = PlayBuilder.nfl("g").period(5).clock(3000).score(20, 20);
        Sequence s = seq().play(ot, ot.next().fin().score(26, 20));
        assertEquals(List.of("Touchdown Bears! Bears 26, Raiders 20"), s.texts());
    }

    @Test
    void pendingTryWhenTheGameEndsIsDropped() {
        PlayBuilder q4 = PlayBuilder.nfl("g").period(4).clock(10).score(14, 17);
        Sequence s = seq().play(q4, q4.next().clock(0).score(20, 17), q4.next().next().fin().clock(0).score(20, 17));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
    }

    @Test
    void overturnedTouchdownDropsTheTry() {
        Sequence s = touchdown().play(after(5000, 0, 0), after(4900, 0, 0), after(4800, 0, 0));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
        assertNull(s.memory().pending);
    }
}

package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class CloseLateDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new CloseLateDetector(LABELS));
    }

    private static final PlayBuilder Q4 = PlayBuilder.nfl("g").period(4).clock(4000).score(20, 17);

    @Test
    void oneGlitchedSnapshotNoAlert() {
        PlayBuilder q2 = PlayBuilder.nfl("g").period(2).clock(4000).score(20, 17);
        assertEquals(List.of(), seq().play(q2, q2.next().period(4).clock(0), q2.next().next().period(4).clock(5400)).ids());
    }

    @Test
    void twoLatePlaysOneAlert() {
        Sequence s = seq().play(Q4, Q4.next().clock(2900), Q4.next().next().clock(2800), Q4.next().next().next().clock(2700));
        assertEquals(List.of("g:CLOSE_LATE"), s.ids());
        Alert a = s.alerts().get(0);
        assertEquals("CLOSE_LATE", a.type());
        assertEquals(Alert.P_CLOSE, a.priority());
        assertEquals("Close game late: Bears 20, Raiders 17 · Q4 4:40", a.text());
    }

    @Test
    void gapMustHoldNow() {
        PlayBuilder wide = Q4.next().score(30, 17);
        Sequence s = seq().play(wide, wide.next().clock(2900), wide.next().next().clock(2800));
        assertEquals(List.of(), s.ids());
        s.play(wide.next().next().next().clock(2700).score(30, 24));
        assertEquals(List.of("g:CLOSE_LATE"), s.ids());
    }

    @Test
    void healedAboveThenUnderAgainNoSecond() {
        Sequence s = seq().play(Q4, Q4.next().clock(2900), Q4.next().next().clock(2800),
            Q4.next().next().next().clock(4000), Q4.next().next().next().next().clock(2900),
            Q4.next().next().next().next().next().clock(2800));
        assertEquals(List.of("g:CLOSE_LATE"), s.ids());
    }

    @Test
    void overtimeNever() {
        PlayBuilder ot = PlayBuilder.nfl("g").period(5).clock(4000).score(20, 20);
        assertEquals(List.of(), seq().play(ot, ot.next().clock(2000), ot.next().next().clock(1000)).ids());
    }

    @Test
    void thresholdsPerSport() {
        PlayBuilder nba = PlayBuilder.nba("g").period(4).clock(2900).score(100, 94);
        assertEquals(List.of(), seq().play(nba, nba.next().clock(2800), nba.next().next().clock(2700)).ids());
        PlayBuilder nhl = PlayBuilder.nhl("g").period(3).clock(3100).score(2, 1);
        assertEquals(List.of("g:CLOSE_LATE"), seq().play(nhl, nhl.next().clock(2800), nhl.next().next().clock(2700)).ids());
    }

    @Test
    void mlbEighthNoTwoPlayRule() {
        PlayBuilder seventh = PlayBuilder.mlb("g").period(7).half("END").score(3, 2);
        Sequence s = seq().play(seventh, seventh.next().period(8).half("TOP"));
        assertEquals(List.of("g:CLOSE_LATE"), s.ids());
        assertEquals("Close game late: Cubs 3, Cardinals 2 · Top 8th", s.texts().get(0));
    }

    @Test
    void mlbExtraInningsNever() {
        PlayBuilder ninth = PlayBuilder.mlb("g").period(9).half("END").score(9, 2);
        assertEquals(List.of(), seq().play(ninth, ninth.next().period(10).half("TOP").score(3, 3)).ids());
    }

    @Test
    void missingClockIsNeverLate() {
        PlayBuilder q4 = Q4.next().clock(-1);
        assertEquals(List.of(), seq().play(q4, q4.next(), q4.next().next()).ids());
    }

    @Test
    void baselineWhileCloseLateMarksFired() {
        PlayBuilder q4 = Q4.next().clock(1800);
        assertEquals(List.of(), seq().play(q4, q4.next().clock(1700), q4.next().next().clock(1600)).ids());
    }

    @Test
    void glitchInsideTheLateWindowIsNotClose() {
        PlayBuilder p = PlayBuilder.nfl("g").period(4).clock(2900).score(30, 10);
        Sequence s = Sequence.of(new ScoreDetector(LABELS), new CloseLateDetector(LABELS))
            .play(p, p.next().clock(2800), p.next().clock(2700).score(17, 10));
        assertEquals(List.of(), s.ids());
    }
}

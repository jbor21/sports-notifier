package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class LeadChangeDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new LeadChangeDetector(LABELS));
    }

    @Test
    void throughATie() {
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(12000).score(1, 0);
        Sequence s = seq().play(p, p.next().score(1, 1), p.next().next().score(1, 2));
        assertEquals(List.of("g:LEAD_CHANGE:1"), s.ids());
        Alert a = s.alerts().get(0);
        assertEquals("LEAD_CHANGE", a.type());
        assertEquals(Alert.P_LEAD, a.priority());
        assertEquals(Side.AWAY, a.side());
        assertEquals("Rangers take the lead. Rangers 2, Bruins 1 · 2nd 20:00", a.text());
    }

    @Test
    void withoutATie() {
        PlayBuilder p = PlayBuilder.nfl("g").period(3).clock(5000).score(7, 3);
        assertEquals(List.of("g:LEAD_CHANGE:1"), seq().play(p, p.next().score(7, 10)).ids());
    }

    @Test
    void firstLeadIsNotAChange() {
        PlayBuilder p = PlayBuilder.nhl("g").period(1).clock(12000);
        assertEquals(List.of(), seq().play(p, p.next().score(1, 0)).ids());
    }

    @Test
    void retakingYourOwnLeadIsNotAChange() {
        PlayBuilder p = PlayBuilder.nhl("g").period(1).clock(12000).score(1, 0);
        assertEquals(List.of(), seq().play(p, p.next().score(1, 1), p.next().next().score(2, 1)).ids());
    }

    @Test
    void nbaBeforeQ4NoAlertButTracked() {
        PlayBuilder q1 = PlayBuilder.nba("g").period(1).clock(3000).score(10, 12);
        Sequence s = seq().play(q1, q1.next().period(2).score(52, 50), q1.next().next().period(4).score(80, 82));
        assertEquals(List.of("g:LEAD_CHANGE:1"), s.ids());
        assertTrue(s.texts().get(0).startsWith("Lakers take the lead."));
    }

    @Test
    void idsCount() {
        PlayBuilder p = PlayBuilder.nhl("g").period(1).clock(12000).score(1, 0);
        Sequence s = seq().play(p, p.next().score(1, 2), p.next().next().score(3, 2), p.next().next().next().score(3, 4));
        assertEquals(List.of("g:LEAD_CHANGE:1", "g:LEAD_CHANGE:2", "g:LEAD_CHANGE:3"), s.ids());
    }

    @Test
    void correctionAloneIsNotALeadChange() {
        // The score went down for the leader; nobody took the lead by scoring.
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(6000).score(3, 2);
        assertEquals(List.of(), seq().play(p, p.next().score(1, 2)).ids());
    }

    @Test
    void notBeforeTheGame() {
        PlayBuilder pre = PlayBuilder.nhl("g").pre();
        assertEquals(List.of(), seq().play(pre.score(0, 1), pre.next().fin().period(3).score(3, 1)).ids());
    }
}

package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class PeriodEndDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new PeriodEndDetector(LABELS));
    }

    @Test
    void fromEndPeriodStage() {
        PlayBuilder q1 = PlayBuilder.nfl("g").period(1).clock(300).score(7, 3);
        Sequence s = seq().play(q1, q1.next().stage("END_PERIOD").clock(0));
        assertEquals(List.of("g:END_OF_PERIOD:1"), s.ids());
        Alert a = s.alerts().get(0);
        assertEquals("END_OF_PERIOD", a.type());
        assertEquals(List.of("END_OF_PERIOD"), a.switches());
        assertEquals(Alert.P_PERIOD, a.priority());
        assertEquals("End of Q1: Bears 7, Raiders 3", a.text());
    }

    @Test
    void fromPeriodNumberWhenStageMissed() {
        PlayBuilder q1 = PlayBuilder.nfl("g").period(1).clock(300);
        assertEquals(List.of("g:END_OF_PERIOD:1"), seq().play(q1, q1.next().period(2).clock(8900)).ids());
    }

    @Test
    void stageThenNumberFiresOnce() {
        PlayBuilder q1 = PlayBuilder.nfl("g").period(1).clock(300);
        PlayBuilder end = q1.next().stage("END_PERIOD").clock(0);
        assertEquals(List.of("g:END_OF_PERIOD:1"), seq().play(q1, end, end.next().stage("IN_PLAY").period(2).clock(9000)).ids());
    }

    @Test
    void lastRegulationPeriodSendsNothing() {
        PlayBuilder q4 = PlayBuilder.nfl("g").period(4).clock(100).score(20, 20);
        PlayBuilder end = q4.next().stage("END_PERIOD").clock(0);
        assertEquals(List.of(), seq().play(q4, end, end.next().stage("IN_PLAY").period(5).clock(6000)).ids());
    }

    @Test
    void overtimePeriodEndSendsNothing() {
        PlayBuilder ot = PlayBuilder.nba("g").period(5).clock(100);
        assertEquals(List.of(), seq().play(ot, ot.next().period(6).clock(3000)).ids());
    }

    @Test
    void halftimeNotEndOfQ2() {
        PlayBuilder q2 = PlayBuilder.nfl("g").period(2).clock(100).score(14, 10);
        Sequence s = seq().play(q2, q2.next().stage("HALFTIME").clock(0), q2.next().next().period(3).clock(9000));
        assertEquals(List.of("g:HALFTIME"), s.ids());
        assertEquals("HALFTIME", s.alerts().get(0).type());
        assertEquals("Halftime: Bears 14, Raiders 10", s.alerts().get(0).text());
    }

    @Test
    void nhlFirstAndSecond() {
        PlayBuilder p1 = PlayBuilder.nhl("g").period(1).clock(100).score(1, 0);
        Sequence s = seq().play(p1, p1.next().period(2).clock(12000), p1.next().next().period(3).clock(12000),
            p1.next().next().next().period(3).stage("END_PERIOD").clock(0));
        assertEquals(List.of("g:END_OF_PERIOD:1", "g:END_OF_PERIOD:2"), s.ids());
        assertEquals(List.of("End of 1st: Bruins 1, Rangers 0", "End of 2nd: Bruins 1, Rangers 0"), s.texts());
    }

    @Test
    void skippedPeriodsAlertOnlyTheLatest() {
        PlayBuilder q1 = PlayBuilder.nba("g").period(1).clock(3000);
        assertEquals(List.of("g:HALFTIME"), seq().play(q1, q1.next().period(3).clock(7000)).ids());
    }

    @Test
    void inningsOneToEightFire() {
        PlayBuilder b = PlayBuilder.mlb("g").period(1).half("TOP").score(3, 1);
        Sequence s = seq().play(b);
        for (int i = 1; i <= 8; i++) {
            b = b.next().period(i).half("BOTTOM");
            PlayBuilder end = b.next().half("END");
            s.play(b, end);
            b = end;
        }
        assertEquals(List.of("g:END_OF_INNING:1", "g:END_OF_INNING:2", "g:END_OF_INNING:3", "g:END_OF_INNING:4",
            "g:END_OF_INNING:5", "g:END_OF_INNING:6", "g:END_OF_INNING:7", "g:END_OF_INNING:8"), s.ids());
        assertEquals("End of 4th: Cubs 3, Cardinals 1", s.texts().get(3));
    }

    @Test
    void inningEndFromTheNextInning() {
        PlayBuilder b = PlayBuilder.mlb("g").period(4).half("BOTTOM");
        assertEquals(List.of("g:END_OF_INNING:4"), seq().play(b, b.next().period(5).half("TOP")).ids());
    }

    @Test
    void middleOfAnInningIsNotItsEnd() {
        PlayBuilder b = PlayBuilder.mlb("g").period(4).half("TOP");
        assertEquals(List.of(), seq().play(b, b.next().half("MIDDLE")).ids());
    }

    @Test
    void ninthNeverFires() {
        PlayBuilder tied = PlayBuilder.mlb("g").period(9).half("BOTTOM").score(3, 3);
        assertEquals(List.of(), seq().play(tied, tied.next().half("END")).ids());
        PlayBuilder ahead = PlayBuilder.mlb("g").period(9).half("TOP").score(4, 3);
        assertEquals(List.of(), seq().play(ahead, ahead.next().half("END")).ids());
    }

    @Test
    void tiedTenthFires() {
        PlayBuilder b = PlayBuilder.mlb("g").period(10).half("BOTTOM").score(3, 3);
        Sequence s = seq().play(b, b.next().half("END"));
        assertEquals(List.of("g:END_OF_INNING:10"), s.ids());
        assertEquals(List.of("END_OF_INNING"), s.alerts().get(0).switches());
    }

    @Test
    void inningSwitchesIncludeChosenSet() {
        PlayBuilder b = PlayBuilder.mlb("g").period(4).half("BOTTOM");
        Alert a = seq().play(b, b.next().half("END")).alerts().get(0);
        assertEquals(List.of("END_OF_INNING", "END_OF_INNING:4"), a.switches());
    }

    @Test
    void halftimeWithPeriodAlreadyThreeIsEndOfQ2() {
        // nfl-401872965 (recording 20261004T151503Z): ESPN showed STATUS_HALFTIME with period 3 and 15:00.
        PlayBuilder q2 = PlayBuilder.nfl("g").period(2).clock(100).score(6, 10);
        PlayBuilder half = q2.next().period(3).clock(9000).stage("HALFTIME");
        PlayBuilder q3 = half.next().stage("IN_PLAY").clock(8000);
        PlayBuilder q4 = q3.next().period(4).clock(9000).score(13, 20);
        Sequence s = seq().play(q2, half, q3, q4);
        assertEquals(List.of("g:HALFTIME", "g:END_OF_PERIOD:3"), s.ids());
        assertEquals("End of Q3: Raiders 20, Bears 13", s.texts().get(1));
    }

    @Test
    void realHalftimeSnapshotWithPeriodThree() throws Exception {
        // ESPN's own snapshot (recording nfl/20261004T151503Z, Colts at Commanders): HALFTIME, period 3, 15:00.
        String json;
        try (var in = PeriodEndDetectorTest.class.getResourceAsStream("/espn/nfl-halftime-period3.json")) {
            json = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        model.GameSnapshot snap = feed.EspnScoreFeed.parse(json, "NFL").get(0);
        assertEquals("HALFTIME", snap.status());
        assertEquals(3, snap.period());
        Play q2 = PlayBuilder.nfl(snap.gameId()).teams("WSH", "IND").period(2).clock(300).score(6, 10).build();
        Play half = Play.of(feed.PollDecision.decide(java.util.Map.of("status", "IN_PROGRESS", "homeScore", "6",
            "awayScore", "10", "lastSeq", "10200000000016"), snap, 0L).orElseThrow().withVersion(2));
        Detectors chain = new Detectors(List.of(new PeriodEndDetector(LABELS)));
        Detectors.Result r = chain.run(chain.run(null, q2).after(), half);
        assertEquals(List.of(snap.gameId() + ":HALFTIME"), r.moment().stream().map(Alert::id).toList());
        assertFalse(r.after().fired("END_OF_PERIOD:3"), "Q3 has not ended");
    }
}

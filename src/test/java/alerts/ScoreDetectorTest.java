package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class ScoreDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new ScoreDetector(LABELS));
    }

    @Test
    void scoreReversalScoreGivesTwoAlerts() {
        // The user's test: an overturned goal and then a real one must not share an id, or dedup drops the real one.
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(12000).score(2, 0);
        Sequence s = seq().play(p, p.next().score(3, 0), p.next().next().score(2, 0), p.next().next().next().score(3, 0));
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:home:2"), s.ids());
        assertEquals(List.of("GOAL", "GOAL"), s.alerts().stream().map(Alert::type).toList());
    }

    @Test
    void mlbOneAlertPerTeamPerPlay() {
        PlayBuilder p = PlayBuilder.mlb("g").period(4).half("BOTTOM").score(1, 0);
        Sequence s = seq().play(p, p.next().score(3, 0), p.next().next().score(4, 0));
        assertEquals(List.of("Cubs score 2! Cubs 3, Cardinals 0 · Bot 4th", "Cubs score! Cubs 4, Cardinals 0 · Bot 4th"), s.texts());
        Alert a = s.alerts().get(0);
        assertEquals("RUN_SCORED", a.type());
        assertEquals(List.of("RUN_SCORED"), a.switches());
        assertEquals(Alert.P_SCORE, a.priority());
        assertEquals(Side.HOME, a.side());
    }

    @Test
    void nhlTwoGoalsOneAlert() {
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(12000);
        Sequence s = seq().play(p, p.next().score(2, 0), p.next().next().score(2, 1));
        assertEquals(List.of("2 goals Bruins! Bruins 2, Rangers 0 · 2nd 20:00", "Goal Rangers! Bruins 2, Rangers 1 · 2nd 20:00"), s.texts());
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:away:1"), s.ids());
    }

    @Test
    void nbaNoScoreAlerts() {
        PlayBuilder p = PlayBuilder.nba("g").period(2).clock(3000).score(50, 48);
        assertEquals(List.of(), seq().play(p, p.next().score(52, 51)).ids());
    }

    @Test
    void bothTeamsScoredTwoAlerts() {
        PlayBuilder p = PlayBuilder.nhl("g").period(1).clock(6000);
        assertEquals(List.of("g:SCORE:home:1", "g:SCORE:away:1"), seq().play(p, p.next().score(1, 1)).ids());
    }

    @Test
    void riseAndCorrectionInOnePlay() {
        PlayBuilder p = PlayBuilder.nhl("g").period(2).clock(6000).score(1, 2);
        Sequence s = seq().play(p, p.next().score(2, 1));
        assertEquals(List.of("g:SCORE:home:1"), s.ids());
    }

    @Test
    void afterFinalQuiet() {
        PlayBuilder f = PlayBuilder.nhl("g").fin().period(3).score(3, 2);
        assertEquals(List.of(), seq().play(f, f.next().score(3, 3)).ids());
    }

    @Test
    void scoreOnThePlayThatEndsTheGameAlerts() {
        PlayBuilder ot = PlayBuilder.nhl("g").period(4).clock(2000).score(2, 2);
        assertEquals(List.of("g:SCORE:home:1"), seq().play(ot, ot.next().fin().score(3, 2)).ids());
    }

    @Test
    void preToFinalSendsOnlyTheFinal() {
        // Review Focus 2: the poller or Stage 1 was down for the whole game.
        PlayBuilder pre = PlayBuilder.nfl("g").pre();
        Sequence s = Sequence.standard().play(pre, pre.next().fin().period(4).score(24, 17));
        assertEquals(List.of(), s.ids());
        assertEquals(List.of("g:FINAL"), s.finals().stream().map(Alert::id).toList());
    }
}

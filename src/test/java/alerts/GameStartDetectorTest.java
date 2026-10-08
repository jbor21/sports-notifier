package alerts;

import org.junit.jupiter.api.Test;

import java.util.List;

import static alerts.Sequence.LABELS;
import static org.junit.jupiter.api.Assertions.*;

class GameStartDetectorTest {

    private static Sequence seq() {
        return Sequence.of(new GameStartDetector(LABELS));
    }

    @Test
    void preToLive() {
        PlayBuilder pre = PlayBuilder.nfl("g").pre();
        Sequence s = seq().play(pre, pre.next().live().period(1).clock(9000));
        assertEquals(List.of("g:GAME_START"), s.ids());
        Alert a = s.alerts().get(0);
        assertEquals("GAME_START", a.type());
        assertEquals(Alert.P_FLOW, a.priority());
        assertEquals("Kickoff: Raiders at Bears.", a.text());
    }

    @Test
    void wordingPerSport() {
        assertEquals("Tip-off: Lakers at Celtics.", start(PlayBuilder.nba("g")));
        assertEquals("First pitch: Cardinals at Cubs.", start(PlayBuilder.mlb("g")));
        assertEquals("Puck drop: Rangers at Bruins.", start(PlayBuilder.nhl("g")));
    }

    private static String start(PlayBuilder b) {
        PlayBuilder pre = b.pre();
        return seq().play(pre, pre.next().live().period(1)).texts().get(0);
    }

    @Test
    void pregameDelayStillStarts() {
        PlayBuilder pre = PlayBuilder.nfl("g").pre();
        PlayBuilder delayed = pre.next().status("DELAYED").stage("DELAYED");
        assertEquals(List.of("g:GAME_START"), seq().play(pre, delayed, delayed.next().live().period(1)).ids());
    }

    @Test
    void preToFinalIsNotAStart() {
        PlayBuilder pre = PlayBuilder.nfl("g").pre();
        assertEquals(List.of(), seq().play(pre, pre.next().fin().period(4)).ids());
    }

    @Test
    void firstSightLiveIsNotAStart() {
        PlayBuilder live = PlayBuilder.nfl("g").period(1);
        assertEquals(List.of(), seq().play(live, live.next().clock(8000)).ids());
    }
}

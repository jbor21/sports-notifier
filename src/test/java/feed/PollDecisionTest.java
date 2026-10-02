package feed;

import model.GameEvent;
import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static model.GameSnapshot.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class PollDecisionTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final String LIVE_SEQ = "10100001000000";   // phase digit 1
    private static final String PRE_SEQ = "100036000000";      // phase digit 0

    private static GameSnapshot snap(Phase phase, String status, int home, int away) {
        Stage stage = switch (status) {
            case "HALFTIME" -> Stage.HALFTIME;
            case "DELAYED", "POSTPONED", "SUSPENDED" -> Stage.DELAYED;
            default -> Stage.IN_PLAY;
        };
        return new GameSnapshot("espn-1", "NBA", "LAL", "BOS", home, away,
            phase, 2, Half.TOP, 3000, 0, stage, status, NOW);
    }

    private static Map<String, String> record(String status, int home, int away, String lastSeq) {
        return Map.of("status", status, "homeScore", String.valueOf(home),
            "awayScore", String.valueOf(away), "lastSeq", lastSeq);
    }

    private static GameEvent decide(Map<String, String> record, GameSnapshot snap) {
        Optional<GameEvent> event = PollDecision.decide(record, snap, NOW);
        assertTrue(event.isPresent(), "expected an event");
        GameEvent e = event.get();
        long seq = Seq.of(snap);
        assertEquals(seq, e.getSeq());
        assertEquals(snap.gameId() + ":" + seq, e.getEventId());
        assertEquals(NOW, e.getTimestamp());
        assertEquals(snap.status(), e.getGameStatus());
        assertEquals("LAL", e.getHomeTeam());
        assertEquals("BOS", e.getAwayTeam());
        assertEquals("NBA", e.getLeague());
        return e;
    }

    @Test
    void firstSightIsSync() {
        assertEquals("GAME_SYNC", decide(Map.of(), snap(LIVE, "IN_PROGRESS", 50, 48)).getEventType());
    }

    @Test
    void firstSightFinalIsSync() {
        assertEquals("GAME_SYNC", decide(Map.of(), snap(FINAL, "FINAL", 101, 99)).getEventType());
    }

    @Test
    void realFinalIsGameEnd() {
        GameEvent e = decide(record("IN_PROGRESS", 99, 99, LIVE_SEQ), snap(FINAL, "FINAL", 101, 99));
        assertEquals("GAME_END", e.getEventType());
        assertEquals("Final: LAL 101 - 99 BOS", e.getDescription());
        assertEquals("LAL", e.getTeam());
    }

    @Test
    void canceledIsSyncNotEnd() {
        assertEquals("GAME_SYNC", decide(record("SCHEDULED", 0, 0, PRE_SEQ), snap(FINAL, "CANCELED", 0, 0)).getEventType());
    }

    @Test
    void scheduledToLiveIsStartWithScore() {
        GameEvent e = decide(record("SCHEDULED", 0, 0, PRE_SEQ), snap(LIVE, "IN_PROGRESS", 2, 0));
        assertEquals("GAME_START", e.getEventType());
        assertEquals("LAL vs BOS has started", e.getDescription());
        assertEquals(2, e.getHomeScore());
    }

    @Test
    void postponedThenLiveIsStart() {
        assertEquals("GAME_START", decide(record("POSTPONED", 0, 0, PRE_SEQ), snap(LIVE, "IN_PROGRESS", 0, 0)).getEventType());
    }

    @Test
    void scoreUpIsScoreUpdate() {
        GameEvent e = decide(record("IN_PROGRESS", 2, 0, LIVE_SEQ), snap(LIVE, "IN_PROGRESS", 2, 3));
        assertEquals("SCORE_UPDATE", e.getEventType());
        assertEquals("BOS", e.getTeam());
        assertEquals("BOS scores! LAL 2 - 3 BOS", e.getDescription());
    }

    @Test
    void bothScoresUpNamesBiggerRise() {
        GameEvent e = decide(record("IN_PROGRESS", 2, 0, LIVE_SEQ), snap(LIVE, "IN_PROGRESS", 3, 3));
        assertEquals("SCORE_UPDATE", e.getEventType());
        assertEquals("BOS", e.getTeam());
    }

    @Test
    void scoreDownIsSync() {
        assertEquals("GAME_SYNC", decide(record("IN_PROGRESS", 7, 0, LIVE_SEQ), snap(LIVE, "IN_PROGRESS", 0, 0)).getEventType());
    }

    @Test
    void scoreChangeAfterFinalIsSync() {
        assertEquals("GAME_SYNC", decide(record("FINAL", 101, 99, LIVE_SEQ), snap(FINAL, "FINAL", 101, 100)).getEventType());
    }

    @Test
    void statusOnlyChangeIsSync() {
        assertEquals("GAME_SYNC", decide(record("IN_PROGRESS", 50, 48, LIVE_SEQ), snap(LIVE, "HALFTIME", 50, 48)).getEventType());
    }

    @Test
    void clockOnlyChangeIsNothing() {
        assertTrue(PollDecision.decide(record("IN_PROGRESS", 50, 48, LIVE_SEQ), snap(LIVE, "IN_PROGRESS", 50, 48), NOW).isEmpty());
    }

    @Test
    void finalSeenAgainIsNothing() {
        assertTrue(PollDecision.decide(record("FINAL", 101, 99, LIVE_SEQ), snap(FINAL, "FINAL", 101, 99), NOW).isEmpty());
    }
}

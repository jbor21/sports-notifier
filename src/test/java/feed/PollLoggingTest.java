package feed;

import org.junit.jupiter.api.Test;

import static feed.Poller.LogLevel.*;
import static org.junit.jupiter.api.Assertions.*;

class PollLoggingTest {

    private static EventSink.PostResult r(int status, String result) {
        return new EventSink.PostResult(status, result);
    }

    @Test
    void acceptedAlertIsInfo() {
        assertEquals(INFO, Poller.levelFor("SCORE_UPDATE", r(200, "accepted")));
    }

    @Test
    void acceptedSyncIsDebug() {
        assertEquals(DEBUG, Poller.levelFor("GAME_SYNC", r(200, "accepted")));
    }

    @Test
    void duplicateIsDebug() {
        assertEquals(DEBUG, Poller.levelFor("SCORE_UPDATE", r(200, "duplicate")));
    }

    @Test
    void staleAlertIsAWarningNotSent() {
        // A snapshot that got ahead of reality can make every later alert stale. That must be visible.
        assertEquals(WARN, Poller.levelFor("SCORE_UPDATE", r(200, "stale")));
        assertEquals(WARN, Poller.levelFor("GAME_END", r(200, "stale")));
    }

    @Test
    void pendingIsDebug() {
        // The game has an unsent play in the outbox; the next poll sends this again. Not a problem to report.
        assertEquals(DEBUG, Poller.levelFor("SCORE_UPDATE", r(200, "pending")));
        assertEquals(DEBUG, Poller.levelFor("GAME_SYNC", r(200, "pending")));
    }

    @Test
    void staleSyncIsDebug() {
        assertEquals(DEBUG, Poller.levelFor("GAME_SYNC", r(200, "stale")));
    }

    @Test
    void invalidIsError() {
        assertEquals(ERROR, Poller.levelFor("SCORE_UPDATE", r(400, "invalid")));
    }

    @Test
    void serverErrorIsRetryWarning() {
        assertEquals(RETRY, Poller.levelFor("SCORE_UPDATE", r(503, null)));
    }

    @Test
    void sinkReadsTheResultField() {
        assertEquals("stale", HttpEventSink.resultOf("{\"result\":\"stale\"}"));
        assertNull(HttpEventSink.resultOf("<html>oops</html>"));
        assertNull(HttpEventSink.resultOf(null));
    }
}

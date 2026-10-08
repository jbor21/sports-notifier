package service.impl;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import testsupport.PostgresTestSupport;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Notification history from Postgres (spec 7.3). */
class NotificationHistoryTest {

    private static final Instant T0 = Instant.parse("2026-10-07T20:00:00Z");

    private HistoryStore store;
    private NotificationHistory history;
    private String bob;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        store = new HistoryStore(PostgresTestSupport.dataSource());
        history = new NotificationHistory(PostgresTestSupport.dataSource());
        bob = UUID.randomUUID().toString();
    }

    /** Row n of a game, created n seconds after T0 (or at {@code at}). */
    private void row(String user, String gameId, int n, String league, String home, String away, Instant at) throws Exception {
        JsonObject body = new JsonObject();
        body.addProperty("id", gameId + ":v" + n);
        body.addProperty("kind", "game_moment");
        body.addProperty("leagueId", league);
        store.insertBatch(List.of(new HistoryStore.HistoryRow(user, gameId + ":v" + n, at, league, gameId, home, away, body.toString())));
    }

    private void row(String user, int n) throws Exception {
        row(user, "mlb-1", n, "MLB", "mlb-chw", "mlb-cle", T0.plusSeconds(n));
    }

    private static List<String> ids(NotificationHistory.Page page) {
        return page.items().stream().map(i -> i.get("id").getAsString()).toList();
    }

    @Test
    void newestFirstWithCursor() throws Exception {
        for (int n = 1; n <= 25; n++) row(bob, n);
        NotificationHistory.Page p1 = history.list(bob, null, null, null, null, 10);
        assertEquals("mlb-1:v25", ids(p1).get(0));
        assertEquals(10, p1.items().size());
        NotificationHistory.Page p2 = history.list(bob, null, null, null, p1.nextCursor(), 10);
        NotificationHistory.Page p3 = history.list(bob, null, null, null, p2.nextCursor(), 10);
        assertEquals(5, p3.items().size());
        assertNull(p3.nextCursor());
        Set<String> all = new HashSet<>();
        for (NotificationHistory.Page p : List.of(p1, p2, p3)) all.addAll(ids(p));
        assertEquals(25, all.size());
        JsonObject first = p1.items().get(0);
        assertEquals(T0.plusSeconds(25).toString(), first.get("createdAt").getAsString(), "createdAt comes from the row");
    }

    @Test
    void insertDuringPagingNoDuplicateNoGap() throws Exception {
        for (int n = 1; n <= 20; n++) row(bob, n);
        NotificationHistory.Page p1 = history.list(bob, null, null, null, null, 10);
        row(bob, 21);                                                          // a new one arrives
        row(bob, "mlb-1", 99, "MLB", "mlb-chw", "mlb-cle", T0.plusSeconds(5).plusMillis(500)); // a catch-up row, mid-history
        NotificationHistory.Page p2 = history.list(bob, null, null, null, p1.nextCursor(), 10);
        NotificationHistory.Page p3 = history.list(bob, null, null, null, p2.nextCursor(), 10);
        List<String> seen = new ArrayList<>(ids(p1));
        seen.addAll(ids(p2));
        seen.addAll(ids(p3));
        assertEquals(new HashSet<>(seen).size(), seen.size(), "no duplicates");
        for (int n = 1; n <= 20; n++) assertTrue(seen.contains("mlb-1:v" + n), "no gaps: v" + n);
        assertTrue(seen.contains("mlb-1:v99"), "an older row inserted later still shows up further down");
    }

    @Test
    void filters() throws Exception {
        row(bob, "mlb-1", 1, "MLB", "mlb-chw", "mlb-cle", T0.plusSeconds(1));
        row(bob, "nhl-2", 1, "NHL", "nhl-bos", "nhl-tor", T0.plusSeconds(2));
        row(bob, "mlb-3", 1, "MLB", "mlb-nyy", "mlb-chw", T0.plusSeconds(3));
        assertEquals(List.of("mlb-3:v1", "mlb-1:v1"), ids(history.list(bob, "MLB", null, null, null, 20)));
        assertEquals(List.of("mlb-3:v1", "mlb-1:v1"), ids(history.list(bob, null, "mlb-chw", null, null, 20)), "home or away");
        assertEquals(List.of("nhl-2:v1"), ids(history.list(bob, null, null, "nhl-2", null, 20)));
        assertEquals(List.of(), ids(history.list(bob, "NHL", "mlb-chw", null, null, 20)));
    }

    @Test
    void otherUsersRowsNeverShow() throws Exception {
        row(UUID.randomUUID().toString(), 1);
        assertEquals(List.of(), ids(history.list(bob, null, null, null, null, 20)));
        assertEquals(0, history.unreadCount(bob));
        assertEquals(List.of(), ids(history.list("not-a-uuid", null, null, null, null, 20)));
    }

    @Test
    void unreadCountSeenAndClear() throws Exception {
        for (int n = 1; n <= 3; n++) row(bob, "mlb-1", n, "MLB", "mlb-chw", "mlb-cle", Instant.now().minusSeconds(10 - n));
        assertEquals(3, history.unreadCount(bob));
        history.markSeen(bob);
        assertEquals(0, history.unreadCount(bob));
        row(bob, "mlb-1", 4, "MLB", "mlb-chw", "mlb-cle", Instant.now().plusSeconds(1));
        assertEquals(1, history.unreadCount(bob));
        history.clear(bob);
        assertEquals(0, history.unreadCount(bob));
        assertEquals(List.of(), ids(history.list(bob, null, null, null, null, 20)));
    }

    @Test
    void catchUpRowOlderThanSeenIsNotUnread() throws Exception {
        history.markSeen(bob);
        // History catching up after a Postgres outage: the row is older than "seen".
        row(bob, "mlb-1", 1, "MLB", "mlb-chw", "mlb-cle", Instant.now().minusSeconds(60));
        assertEquals(0, history.unreadCount(bob));
        assertEquals(1, history.list(bob, null, null, null, null, 20).items().size(), "but it is in history");
    }

    @Test
    void catchUpRowOlderThanClearStaysHidden() throws Exception {
        history.clear(bob);
        row(bob, "mlb-1", 1, "MLB", "mlb-chw", "mlb-cle", Instant.now().minusSeconds(60));
        assertEquals(List.of(), ids(history.list(bob, null, null, null, null, 20)), "a cleared alert does not come back");
        assertEquals(0, history.unreadCount(bob));
    }

    @Test
    void unreadCountStopsAtAThousand() throws Exception {
        List<HistoryStore.HistoryRow> rows = new ArrayList<>();
        for (int n = 0; n < 1005; n++) {
            rows.add(new HistoryStore.HistoryRow(bob, "m" + n, Instant.now().minusSeconds(n), "MLB", "mlb-1", "a", "b", "{}"));
        }
        store.insertBatch(rows);
        assertEquals(1000, history.unreadCount(bob));
    }

    /**
     * created_at is Stage 1's clock (the Kafka timestamp), which can run a little ahead of Postgres's
     * now(). "Seen" and "clear" use the later of now() and the newest row, so such a row is neither
     * left unread after "seen" nor shown after "clear".
     */
    @Test
    void seenAndClearCoverRowsStampedAheadOfPostgresClock() throws Exception {
        row(bob, "mlb-1", 1, "MLB", "mlb-chw", "mlb-cle", Instant.now().plusSeconds(5));   // Stage 1's clock is 5 s ahead
        assertEquals(1, history.unreadCount(bob));
        history.markSeen(bob);
        assertEquals(0, history.unreadCount(bob), "seen covers the row stamped ahead");
        history.clear(bob);
        row(bob, "mlb-1", 2, "MLB", "mlb-chw", "mlb-cle", Instant.now().plusSeconds(4));   // a catch-up row, also ahead
        assertEquals(List.of(), ids(history.list(bob, null, null, null, null, 20)), "clear hides it");
    }

    @Test
    void retentionDeletesOver30DaysInBatches() throws Exception {
        Instant now = Instant.now();
        for (int n = 0; n < 25; n++) row(bob, "mlb-1", n, "MLB", "a", "b", now.minus(Duration.ofDays(31)).plusSeconds(n));
        row(bob, "mlb-1", 100, "MLB", "a", "b", now.minus(Duration.ofDays(29)));
        assertEquals(25, history.deleteOlderThan(Duration.ofDays(30), 10));
        assertEquals(List.of("mlb-1:v100"), ids(history.list(bob, null, null, null, null, 50)));
    }

    @Test
    void badCursorRejected() {
        for (String bad : List.of("garbage!", "bm90LWEtY3Vyc29y", "MTIz")) {   // not base64; "not-a-cursor"; "123"
            assertThrows(IllegalArgumentException.class, () -> history.list(bob, null, null, null, bad, 10), bad);
        }
    }
}

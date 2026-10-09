package checker;

import catalog.AlertCatalog;
import catalog.Teams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.AccountService;
import service.impl.FollowService;
import testsupport.PostgresTestSupport;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** What the checker reads from Postgres (Phase 4.1 spec, 3.2 and 6.2). */
class SnapshotsTest {

    private JedisPool pool;
    private FollowService follows;
    private String u1;
    private String u2;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        AccountService accounts = new AccountService(PostgresTestSupport.dataSource());
        u1 = accounts.signup("ck_abc123_0", "checker-pass", null).body().id();
        u2 = accounts.signup("ck_abc123_1", "checker-pass", null).body().id();
        follows = new FollowService(PostgresTestSupport.dataSource(), pool, AlertCatalog.load(), Teams.load(),
            id -> Optional.of(Map.of("league", "NHL")));
    }

    @AfterEach
    void tearDown() {
        pool.close();
    }

    static void history(String user, String messageId, String gameId, Instant at) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO notifications (user_id, message_id, created_at, league, game_id, body)"
                 + " VALUES (?, ?, ?, 'NHL', ?, ?::jsonb)")) {
            ps.setObject(1, UUID.fromString(user));
            ps.setString(2, messageId);
            ps.setTimestamp(3, Timestamp.from(at));
            ps.setString(4, gameId);
            ps.setString(5, "{\"id\":\"" + messageId + "\"}");
            ps.executeUpdate();
        }
    }

    @Test
    void followsIncludeUpdatedAtAndExpiresAt() {
        follows.create(u1, "team", "nhl-det");
        follows.create(u1, "game", "nhl-rpabc123-1");
        follows.create(u2, "league", "NHL");
        List<FollowSnapshot> got = Snapshots.follows(PostgresTestSupport.dataSource(), List.of(u1));
        assertEquals(2, got.size());
        for (FollowSnapshot s : got) {
            assertEquals(u1, s.follow().userId());
            assertNotNull(s.updatedAt());
            assertFalse(s.follow().alerts().isEmpty());
        }
    }

    @Test
    void historyOnlyThisRunsGames() throws Exception {
        Instant t = Instant.parse("2026-10-09T12:00:00Z");
        history(u1, "nhl-rpabc123-1:GAME_START", "nhl-rpabc123-1", t);
        history(u1, "nhl-401891803:GAME_START", "nhl-401891803", t);
        history(u2, "nhl-rpabc123-1:GAME_START", "nhl-rpabc123-1", t);
        List<HistoryRow> got = Snapshots.history(PostgresTestSupport.dataSource(), List.of(u1), RunId.gameMarker("abc123"));
        assertEquals(1, got.size());
        assertEquals("nhl-rpabc123-1:GAME_START", got.get(0).messageId());
        assertEquals(t, got.get(0).createdAt());
        assertEquals("nhl-rpabc123-1:GAME_START", got.get(0).body().get("id").getAsString());
    }

    @Test
    void dbNowIsPostgresTime() {
        Instant now = Snapshots.dbNow(PostgresTestSupport.dataSource());
        assertTrue(Math.abs(now.toEpochMilli() - System.currentTimeMillis()) < 5_000);
    }
}

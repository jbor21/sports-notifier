package checker;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.impl.AccountService;
import service.impl.FollowSets;
import testsupport.PostgresTestSupport;
import util.RedisKeys;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic users don't pile up in the persistent Postgres (Phase 4.1 spec, 7). */
class CleanupTest {

    private JedisPool pool;
    private Jedis jedis;
    private AccountService accounts;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        accounts = new AccountService(PostgresTestSupport.dataSource());
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** A user with a history row, a read marker, and an unread counter. */
    private String user(String name) throws Exception {
        String id = accounts.signup(name, "checker-pass", null).body().id();
        SnapshotsTest.history(id, "g:1", "nhl-rpabc123-1", Instant.now());
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO notification_reads (user_id) VALUES (?)")) {
            ps.setObject(1, UUID.fromString(id));
            ps.executeUpdate();
        }
        jedis.set(RedisKeys.UNREAD_PREFIX + id, "1");
        return id;
    }

    private static List<String> usernames() throws Exception {
        List<String> out = new ArrayList<>();
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT username FROM users ORDER BY username")) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static int rows(String table) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             ResultSet rs = c.createStatement().executeQuery("SELECT count(*) FROM " + table)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void removesOnlyThisRunsUsers() throws Exception {
        user("ck_abc123_0");
        user("ck_abc123_1");
        user("ck_zzz999_0");
        user("bob");
        assertEquals(2, Cleanup.deleteRun(PostgresTestSupport.dataSource(), pool, "abc123"));
        assertEquals(List.of("bob", "ck_zzz999_0"), usernames());
    }

    @Test
    void removesHistoryReadsAndUnreadKeys() throws Exception {
        String gone = user("ck_abc123_0");
        String kept = user("bob");
        Cleanup.deleteRun(PostgresTestSupport.dataSource(), pool, "abc123");
        assertEquals(1, rows("notifications"));
        assertEquals(1, rows("notification_reads"));
        assertFalse(jedis.exists(RedisKeys.UNREAD_PREFIX + gone));
        assertTrue(jedis.exists(RedisKeys.UNREAD_PREFIX + kept));
    }

    @Test
    void cleanAllRemovesEveryCheckerUserAndDeletesMarker() throws Exception {
        user("ck_abc123_0");
        user("ck_zzz999_17");
        user("bob");
        user("ck_bob");                                  // a person's name, not a run's
        FollowSets.markBuilt(jedis, "r");
        assertEquals(2, Cleanup.cleanAll(PostgresTestSupport.dataSource(), pool));
        assertEquals(List.of("bob", "ck_bob"), usernames());
        assertFalse(jedis.exists(RedisKeys.SUB_BUILT), "the sets are rebuilt from Postgres without them");
    }

    @Test
    void usersRemovedWithoutUnfollowForceARebuild() throws Exception {
        // A setup that failed part way: users exist whose sessions the checker never got back, so their
        // follows were never removed through the API and they are still in the sub:* sets.
        user("ck_abc123_0");
        user("ck_abc123_1");
        FollowSets.markBuilt(jedis, "r");
        Api unreachable = new Api(java.net.URI.create("http://localhost:1"), "", 1, java.time.Duration.ZERO);
        assertEquals(2, Cleanup.run(unreachable, List.of(), java.util.Map.of(), PostgresTestSupport.dataSource(), pool, "abc123"));
        assertFalse(jedis.exists(RedisKeys.SUB_BUILT), "the sets are rebuilt from Postgres without them");
    }

    @Test
    void cleanRunKeepsTheMarker() throws Exception {
        FollowSets.markBuilt(jedis, "r");
        Api unreachable = new Api(java.net.URI.create("http://localhost:1"), "", 1, java.time.Duration.ZERO);
        assertEquals(0, Cleanup.run(unreachable, List.of(), java.util.Map.of(), PostgresTestSupport.dataSource(), pool, "abc123"));
        assertTrue(jedis.exists(RedisKeys.SUB_BUILT));
    }
}

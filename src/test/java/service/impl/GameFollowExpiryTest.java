package service.impl;

import catalog.AlertCatalog;
import catalog.Teams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.PostgresTestSupport;
import util.RedisKeys;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Game follows expire a day after the game ends (spec 5.5). */
class GameFollowExpiryTest {

    private static final Instant NOW = Instant.parse("2026-10-07T20:00:00Z");

    private JedisPool pool;
    private Jedis jedis;
    private FollowService follows;
    private GameFollowExpiry expiry;
    private String bob;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        bob = new AccountService(PostgresTestSupport.dataSource()).signup("bob", "password-123", null).body().id();
        follows = new FollowService(PostgresTestSupport.dataSource(), pool, AlertCatalog.load(), Teams.load(), id -> {
            Map<String, String> r = jedis.hgetAll(RedisKeys.GAME_PREFIX + id);
            return r.isEmpty() ? Optional.empty() : Optional.of(r);
        });
        expiry = new GameFollowExpiry(PostgresTestSupport.dataSource(), pool, follows);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private Follow followGame(String gameId, String status) {
        jedis.hset(RedisKeys.GAME_PREFIX + gameId, Map.of("homeTeam", "CHW", "awayTeam", "CLE", "league", "MLB", "status", status));
        return follows.create(bob, "game", gameId).body();
    }

    @Test
    void finishedGamesGetAnExpiryADayOut() {
        for (String status : new String[] {"FINAL", "POSTPONED", "CANCELED"}) {
            Follow f = followGame("mlb-" + status, status);
            expiry.runOnce(NOW);
            assertEquals(NOW.plus(Duration.ofDays(1)), follows.get(bob, f.id()).body().expiresAt(), status);
        }
    }

    @Test
    void liveGameUntouched() {
        Follow f = followGame("mlb-1", "IN_PROGRESS");
        expiry.runOnce(NOW);
        assertNull(follows.get(bob, f.id()).body().expiresAt());
    }

    @Test
    void pastExpiryIsDeletedAndLeavesItsSets() {
        Follow f = followGame("mlb-1", "FINAL");
        assertTrue(jedis.sismember("sub:game:mlb-1:FINAL", bob));
        expiry.runOnce(NOW);
        assertEquals(1, expiry.runOnce(NOW.plus(Duration.ofDays(1)).plusSeconds(1)));
        assertEquals(404, follows.get(bob, f.id()).status());
        assertFalse(jedis.sismember("sub:game:mlb-1:FINAL", bob));
    }

    @Test
    void goneRecordOlderThanEightDaysIsDeleted() throws Exception {
        Follow old = followGame("mlb-old", "SCHEDULED");
        Follow recent = followGame("mlb-new", "SCHEDULED");
        jedis.del(RedisKeys.GAME_PREFIX + "mlb-old", RedisKeys.GAME_PREFIX + "mlb-new");
        setCreatedAt(old.id(), NOW.minus(Duration.ofDays(9)));
        setCreatedAt(recent.id(), NOW.minus(Duration.ofDays(2)));
        assertEquals(1, expiry.runOnce(NOW));
        assertEquals(404, follows.get(bob, old.id()).status());
        assertEquals(200, follows.get(bob, recent.id()).status());
    }

    @Test
    void teamFollowsNeverExpire() {
        Follow team = follows.create(bob, "team", "mlb-chw").body();
        expiry.runOnce(NOW.plus(Duration.ofDays(30)));
        assertEquals(200, follows.get(bob, team.id()).status());
    }

    private static void setCreatedAt(String id, Instant at) throws Exception {
        try (Connection c = PostgresTestSupport.dataSource().getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE follows SET created_at = ? WHERE id = ?")) {
            ps.setTimestamp(1, Timestamp.from(at));
            ps.setObject(2, UUID.fromString(id));
            ps.executeUpdate();
        }
    }
}

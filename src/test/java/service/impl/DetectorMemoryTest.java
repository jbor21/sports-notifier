package service.impl;

import alerts.GameMemory;
import alerts.PlayBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 1's per-game detector memory, det:<gameId> (Phase 3.3 spec, 2.2 and 2.3). */
class DetectorMemoryTest {

    private JedisPool pool;
    private Jedis jedis;
    private DetectorMemory memory;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        memory = new DetectorMemory(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private static GameMemory at(int homeScore) {
        GameMemory m = new GameMemory();
        m.takePosition(PlayBuilder.nhl("nhl-1").score(homeScore, 0).build());
        return m;
    }

    @Test
    void missingIsNullMemory() {
        DetectorMemory.Stored s = memory.read("nhl-1");
        assertEquals(0L, s.version());
        assertNull(s.memory());
    }

    @Test
    void writeThenRead() {
        assertTrue(memory.writeIfHigher("nhl-1", 1_790_000_000_005L, at(3)));
        DetectorMemory.Stored s = memory.read("nhl-1");
        assertEquals(1_790_000_000_005L, s.version(), "clock-seeded versions are 13 digits and must stay exact");
        assertEquals(at(3).toJson(), s.memory().toJson());
    }

    @Test
    void olderVersionDoesNotOverwrite() {
        // A stalled worker that lost its lanes finishes an older play after the new owner moved on.
        assertTrue(memory.writeIfHigher("nhl-1", 5, at(5)));
        assertFalse(memory.writeIfHigher("nhl-1", 4, at(4)));
        assertFalse(memory.writeIfHigher("nhl-1", 5, at(4)));
        assertEquals(at(5).toJson(), memory.read("nhl-1").memory().toJson());
    }

    @Test
    void ttlIsEightDays() {
        memory.writeIfHigher("nhl-1", 1, at(0));
        long ttl = jedis.ttl("det:nhl-1");
        assertTrue(ttl > 691_000 && ttl <= 691_200, "outlives game-events' 7-day retention: " + ttl);
    }
}

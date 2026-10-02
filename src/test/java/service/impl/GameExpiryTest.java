package service.impl;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import static org.junit.jupiter.api.Assertions.*;

class GameExpiryTest {

    private static final long DAY = 86_400L;

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new EventProcessingServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private void accept(String gameId, String type, String status, long seq) {
        service.processEvent(new GameEvent(gameId, gameId + ":" + seq, type, "LAL", 1000L, "d",
            "LAL", "BOS", 1, 0, status, "NBA", seq));
    }

    @Test
    void finalGameExpiresWithinADay() {
        accept("g1", "GAME_END", "FINAL", 20);
        long ttl = jedis.ttl("game:g1");
        assertTrue(ttl > 0 && ttl <= DAY, "ttl " + ttl);
    }

    @Test
    void canceledGameExpiresWithinADay() {
        accept("g1", "GAME_SYNC", "CANCELED", 20);
        long ttl = jedis.ttl("game:g1");
        assertTrue(ttl > 0 && ttl <= DAY, "ttl " + ttl);
    }

    @Test
    void liveGameKeepsEightDays() {
        accept("g1", "SCORE_UPDATE", "IN_PROGRESS", 10);
        long ttl = jedis.ttl("game:g1");
        assertTrue(ttl > DAY && ttl <= 8 * DAY, "ttl " + ttl);
    }

    @Test
    void acceptedEventJoinsRecentList() {
        accept("g1", "SCORE_UPDATE", "IN_PROGRESS", 10);
        assertNotNull(jedis.zscore("games:recent", "g1"));
    }

    @Test
    void staleEventLeavesTtlAlone() {
        accept("g1", "GAME_END", "FINAL", 20);
        accept("g1", "SCORE_UPDATE", "IN_PROGRESS", 10);   // stale: must not reset the TTL to 8 days
        long ttl = jedis.ttl("game:g1");
        assertTrue(ttl > 0 && ttl <= DAY, "ttl " + ttl);
    }
}

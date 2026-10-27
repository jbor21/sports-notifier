package service.impl;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import util.RedisKeys;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** The set of checker users that replayed games may reach (#47). */
class SyntheticUsersTest {

    private JedisPool pool;
    private Jedis jedis;
    private final AtomicInteger rebuilds = new AtomicInteger();

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void addsOnlyCheckerNames() {
        SyntheticUsers synthetic = new SyntheticUsers(pool, rebuilds::incrementAndGet);
        synthetic.created("u1", "ck_abc_1");
        synthetic.created("u2", "alice");
        synthetic.created("u3", " CK_abc_2 ");
        assertEquals(java.util.Set.of("u1", "u3"), jedis.smembers(RedisKeys.SYNTHETIC_USERS));
        assertEquals(0, rebuilds.get());
    }

    @Test
    void redisDownAsksForRebuild() {
        try (JedisPool down = new JedisPool(new JedisPoolConfig(), "localhost", 1, 200, null, 1)) {
            SyntheticUsers synthetic = new SyntheticUsers(down, rebuilds::incrementAndGet);
            assertDoesNotThrow(() -> synthetic.created("u1", "ck_abc_1"));
            assertEquals(1, rebuilds.get());
            synthetic.created("u2", "alice");   // not synthetic: Redis is never touched
            assertEquals(1, rebuilds.get());
        }
    }

    @Test
    void isSyntheticMatchesTheCheckerPrefix() {
        assertTrue(SyntheticUsers.isSynthetic("ck_x_1"));
        assertTrue(SyntheticUsers.isSynthetic(" Ck_x_1"));
        assertFalse(SyntheticUsers.isSynthetic("bob"));
        assertFalse(SyntheticUsers.isSynthetic("duck_1"));
        assertFalse(SyntheticUsers.isSynthetic(null));
    }
}

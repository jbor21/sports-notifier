package gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** hello's unread count: Redis first, Postgres (through the Notification service) when missing. */
class UnreadCountTest {

    private JedisPool pool;
    private Jedis jedis;

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
    void missingAsksPostgresOnce() {
        AtomicInteger asked = new AtomicInteger();
        UnreadCount unread = new UnreadCount(pool, user -> { asked.incrementAndGet(); return 3; });
        assertEquals(3, unread.get("u1"));
        assertEquals(3, unread.get("u1"));
        assertEquals(1, asked.get());
        long ttl = jedis.ttl("unread:u1");
        assertTrue(ttl > 0 && ttl <= 86_400, "ttl " + ttl);
    }

    @Test
    void presentSkipsPostgres() {
        jedis.set("unread:u1", "9");
        UnreadCount unread = new UnreadCount(pool, user -> { throw new AssertionError("Postgres asked"); });
        assertEquals(9, unread.get("u1"));
    }

    @Test
    void racingIncrementIsKept() {
        // Postgres said 3, but before our SET NX the key appeared (another tab filled it, then Stage 2
        // counted a push): the value in Redis wins.
        UnreadCount unread = new UnreadCount(pool, user -> { jedis.set("unread:u1", "4"); return 3; });
        assertEquals(4, unread.get("u1"));
        assertEquals("4", jedis.get("unread:u1"));
    }
}

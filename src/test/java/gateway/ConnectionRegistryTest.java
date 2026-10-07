package gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** conn:<userId>: which gateways hold a user's sockets (Phase 3.1 spec, section 3). */
class ConnectionRegistryTest {

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

    private long redisNow() {
        return Long.parseLong(jedis.time().get(0));
    }

    @Test
    void addSetsEntryAndKeyExpiry() {
        new ConnectionRegistry(pool, "A", 90).add("u1");
        double expiresAt = jedis.zscore("conn:u1", "A");
        assertTrue(Math.abs(expiresAt - (redisNow() + 90)) <= 1, "entry expiry is Redis TIME + 90 s");
        long ttl = jedis.ttl("conn:u1");
        assertTrue(ttl > 85 && ttl <= 90, "the key itself expires too: " + ttl);
    }

    @Test
    void twoGatewaysOneUserKeepsBoth() {
        new ConnectionRegistry(pool, "A", 90).add("u1");
        new ConnectionRegistry(pool, "B", 90).add("u1");
        assertEquals(List.of("A", "B"), jedis.zrange("conn:u1", 0, -1));
    }

    @Test
    void removeOnlyRemovesThisGateway() {
        new ConnectionRegistry(pool, "A", 90).add("u1");
        ConnectionRegistry b = new ConnectionRegistry(pool, "B", 90);
        b.add("u1");
        b.remove("u1");
        assertEquals(List.of("A"), jedis.zrange("conn:u1", 0, -1));
    }

    @Test
    void stoppedRenewalsExpireEntryAndKey() throws Exception {
        // A gateway that dies without cleaning up stops renewing; its entry lapses and the key goes away.
        new ConnectionRegistry(pool, "A", 2).add("u1");
        Thread.sleep(3_100);
        assertFalse(jedis.exists("conn:u1"));
    }

    @Test
    void renewAllKeepsEntriesAlive() throws Exception {
        ConnectionRegistry a = new ConnectionRegistry(pool, "A", 2);
        a.add("u1");
        a.add("u2");
        Thread.sleep(1_500);
        a.renewAll(Set.of("u1", "u2"));
        Thread.sleep(1_500);
        assertTrue(jedis.exists("conn:u1"));
        assertTrue(jedis.zscore("conn:u2", "A") >= redisNow());
    }

    @Test
    void removeAllOnShutdownKeepsOtherGateways() {
        ConnectionRegistry a = new ConnectionRegistry(pool, "A", 90);
        a.add("both");
        a.add("onlyA");
        new ConnectionRegistry(pool, "B", 90).add("both");
        a.removeAll(Set.of("both", "onlyA"));
        assertEquals(List.of("B"), jedis.zrange("conn:both", 0, -1));
        assertFalse(jedis.exists("conn:onlyA"));
    }
}

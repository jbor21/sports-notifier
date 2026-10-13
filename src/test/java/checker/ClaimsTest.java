package checker;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Reading Stage 2's delivery times back from its claims (Phase 4.2 spec, 7.1). */
class ClaimsTest {

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

    private static ExpectedPush push(String user, String id) {
        return new ExpectedPush(user, "nhl-rpabc123-1", id, new JsonObject(), 1);
    }

    @Test
    void nonNumericClaimIsNoDeliveryTime() {
        jedis.set("notif:m1:u1", "1");                 // a claim from before claims held a time
        jedis.set("notif:m2:u1", "x");
        jedis.set("notif:m4:u1", "1800000000123");
        Map<String, Long> at = Claims.deliveredAt(pool,
            List.of(push("u1", "m1"), push("u1", "m2"), push("u1", "m3"), push("u1", "m4")));
        assertEquals(Map.of(Verifier.key("u1", "m4"), 1_800_000_000_123L), at);
    }
}

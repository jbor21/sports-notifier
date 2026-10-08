package service.impl;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Seen and clear reset the badge on every tab, on every gateway (Phase 3.4 spec, 8.2). */
class UnreadCounterTest {

    private JedisPool pool;
    private Jedis jedis;
    private Jedis listener;
    private final List<String> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        listener = new Jedis("localhost", 6379);
        CountDownLatch subscribed = new CountDownLatch(1);
        JedisPubSub sub = new JedisPubSub() {
            @Override public void onPMessage(String pattern, String channel, String message) { received.add(channel + "|" + message); }
            @Override public void onPSubscribe(String pattern, int count) { subscribed.countDown(); }
        };
        Thread t = new Thread(() -> listener.psubscribe(sub, "gw:*"));
        t.setDaemon(true);
        t.start();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        listener.close();
        jedis.close();
        pool.close();
    }

    @Test
    void resetSetsZeroWithTtlAndTellsEveryGateway() throws Exception {
        long now = Long.parseLong(jedis.time().get(0));
        jedis.zadd("conn:u1", now + 90, "A");
        jedis.zadd("conn:u1", now + 90, "B");
        jedis.zadd("conn:u1", now - 1, "C");   // expired
        jedis.set("unread:u1", "7");

        new UnreadCounter(pool).reset("u1");

        assertEquals("0", jedis.get("unread:u1"));
        long ttl = jedis.ttl("unread:u1");
        assertTrue(ttl > 0 && ttl <= 86_400, "ttl " + ttl);
        Thread.sleep(300);
        for (String gw : List.of("gw:A", "gw:B")) {
            String m = received.stream().filter(r -> r.startsWith(gw + "|")).findFirst().orElseThrow(() -> new AssertionError(gw));
            var payload = JsonParser.parseString(m.substring(m.indexOf('|') + 1)).getAsJsonObject();
            assertEquals("u1", payload.get("userId").getAsString());
            assertEquals("unread_count", payload.getAsJsonObject("payload").get("type").getAsString());
            assertEquals(0, payload.getAsJsonObject("payload").get("count").getAsInt());
        }
        assertTrue(received.stream().noneMatch(r -> r.startsWith("gw:C|")));
    }
}

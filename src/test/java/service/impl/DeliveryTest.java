package service.impl;

import model.GameEvent;
import model.NotificationMessage;
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

/** Stage 2's deliver script: dedup, history, and publish as one step (Phase 3.1 spec, section 2.3). */
class DeliveryTest {

    private JedisPool pool;
    private Jedis jedis;
    private Jedis listener;
    private Thread listenerThread;
    private final List<String> received = new CopyOnWriteArrayList<>();   // "channel|message"
    private Delivery delivery;

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        delivery = new Delivery(pool);
        // Pub/sub ignores the database number, so this hears publishes from the test's DB 1 too.
        listener = new Jedis("localhost", 6379);
        CountDownLatch subscribed = new CountDownLatch(1);
        JedisPubSub sub = new JedisPubSub() {
            @Override public void onPMessage(String pattern, String channel, String message) { received.add(channel + "|" + message); }
            @Override public void onPSubscribe(String pattern, int count) { subscribed.countDown(); }
        };
        listenerThread = new Thread(() -> listener.psubscribe(sub, "gw:*", "notify:*"));
        listenerThread.start();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        listener.close();
        jedis.close();
        pool.close();
    }

    private long redisNow() {
        return Long.parseLong(jedis.time().get(0));
    }

    private static NotificationMessage msg(String userId, long version) {
        GameEvent play = new GameEvent("nba-1", "nba-1:" + version, "SCORE_UPDATE", "LAL", 1234L, "LAL scores!",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10).withVersion(version);
        return NotificationMessage.of(play, userId);
    }

    private List<String> receivedOn(String channel) throws InterruptedException {
        Thread.sleep(300);
        return received.stream().filter(r -> r.startsWith(channel + "|")).toList();
    }

    @Test
    void sameMessageTwiceIsOneHistoryEntryAndOnePublish() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        assertTrue(delivery.deliver(msg("u1", 1)));
        assertFalse(delivery.deliver(msg("u1", 1)), "Kafka redelivered it; the script must make that a no-op");
        assertEquals(1, jedis.llen("notifications:u1"));
        assertEquals(1, receivedOn("gw:A").size());
        assertTrue(receivedOn("notify:u1").isEmpty(), "no broadcast channel any more: only the user's gateways");
    }

    @Test
    void gatewayMessageNamesTheUserAndCarriesTheHistoryEntry() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        delivery.deliver(msg("u1", 1));
        String gw = receivedOn("gw:A").get(0);
        assertTrue(gw.contains("\"userId\":\"u1\""), gw);
        assertTrue(gw.contains("\"payload\":{"), gw);
        assertTrue(jedis.lindex("notifications:u1", 0).contains("\"message\":\"LAL scores!\""));
    }

    @Test
    void publishesToEveryLiveGatewayOfTheUser() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        jedis.zadd("conn:u1", redisNow() + 90, "C");
        jedis.zadd("conn:u1", redisNow() - 1, "B");        // expired: that gateway stopped renewing
        delivery.deliver(msg("u1", 1));
        assertEquals(1, receivedOn("gw:A").size());
        assertEquals(1, receivedOn("gw:C").size());
        assertEquals(0, receivedOn("gw:B").size());
        assertNull(jedis.zscore("conn:u1", "B"), "expired entries are removed on the way");
    }

    @Test
    void userWithNoSocketGetsHistoryOnly() throws Exception {
        assertTrue(delivery.deliver(msg("u1", 1)));
        assertEquals(1, jedis.llen("notifications:u1"));
        assertTrue(receivedOn("gw:").isEmpty());
    }

    @Test
    void scriptErrorFailsTheBatchAndLeavesNoClaim() {
        // Review finding I1: a pipelined script error was swallowed and the batch committed, so the
        // notification was lost. And Redis does not roll back a script that fails halfway, so a claim
        // written before the failing write would make the retry think it was already delivered.
        jedis.set("notifications:u1", "not a list");                 // LPUSH will fail with WRONGTYPE
        assertThrows(redis.clients.jedis.exceptions.JedisDataException.class,
            () -> delivery.deliverAll(List.of(msg("u1", 1))));
        assertFalse(jedis.exists("notif:nba-1:v1:u1"), "no claim left behind, so the retry delivers it");
    }

    @Test
    void dedupKeyLastsTwoDays() {
        delivery.deliver(msg("u1", 1));
        long ttl = jedis.ttl("notif:nba-1:v1:u1");
        assertTrue(ttl > 172_700 && ttl <= 172_800, "outlives notifications' 1-day retention: " + ttl);
    }

    @Test
    void deliverAllKeepsOrderAndDedupsInsideTheBatch() {
        delivery.deliverAll(List.of(msg("u1", 1), msg("u1", 2), msg("u1", 2), msg("u1", 3)));
        List<String> history = jedis.lrange("notifications:u1", 0, -1);
        assertEquals(3, history.size());
        assertEquals(3, jedis.keys("notif:*").size());
    }
}

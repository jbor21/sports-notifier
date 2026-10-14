package service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import testsupport.TestMessages;

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

/** Stage 2's deliver script: dedup and publish as one step. History is the history writers' job (Phase 3.2 spec, 7.1). */
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
        return TestMessages.of(play, userId);
    }

    private List<String> receivedOn(String channel) throws InterruptedException {
        Thread.sleep(300);
        return received.stream().filter(r -> r.startsWith(channel + "|")).toList();
    }

    @Test
    void sameMessageTwiceIsOnePublish() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        assertTrue(delivery.deliver(msg("u1", 1)));
        assertFalse(delivery.deliver(msg("u1", 1)), "Kafka redelivered it; the script must make that a no-op");
        assertFalse(jedis.exists("notifications:u1"), "no history in Redis: it is in Postgres now");
        assertEquals(1, receivedOn("gw:A").size());
        assertTrue(receivedOn("notify:u1").isEmpty(), "no broadcast channel any more: only the user's gateways");
    }

    @Test
    void gatewayMessageNamesTheUserAndCarriesTheHistoryEntry() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        delivery.deliver(msg("u1", 1));
        String gw = receivedOn("gw:A").get(0);
        assertTrue(gw.contains("\"userId\":\"u1\""), gw);
        JsonObject payload = JsonParser.parseString(gw.substring(gw.indexOf('|') + 1)).getAsJsonObject().getAsJsonObject("payload");
        assertEquals("notification", payload.get("type").getAsString());
        assertEquals("LAL scores!", payload.getAsJsonObject("notification").getAsJsonObject("headline").get("text").getAsString());
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
    void userWithNoSocketIsClaimedWithoutAPush() throws Exception {
        assertTrue(delivery.deliver(msg("u1", 1)));
        assertTrue(jedis.exists("notif:nba-1:v1:u1"), "delivered as far as Stage 2 goes; history has it from the history writers");
        assertTrue(receivedOn("gw:").isEmpty());
    }

    @Test
    void scriptErrorFailsTheBatchAndLeavesNoClaim() {
        // Review finding I1: a pipelined script error was swallowed and the batch committed, so the
        // notification was lost. And Redis does not roll back a script that fails halfway, so a claim
        // written before the failing write would make the retry think it was already delivered.
        jedis.set("conn:u1", "not a sorted set");                    // ZREMRANGEBYSCORE will fail with WRONGTYPE
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

    private long redisNowMillis() {
        List<String> t = jedis.time();
        return Long.parseLong(t.get(0)) * 1000 + Long.parseLong(t.get(1)) / 1000;
    }

    @Test
    void claimHoldsDeliveryTime() {
        long before = redisNowMillis();
        assertTrue(delivery.deliver(msg("u1", 1)));
        long after = redisNowMillis();
        long claimed = Long.parseLong(jedis.get("notif:nba-1:v1:u1"));
        assertTrue(claimed >= before && claimed <= after,
            "the claim is when the push was published, by Redis's clock: " + before + " <= " + claimed + " <= " + after);
    }

    @Test
    void legacyClaimStillDedups() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        jedis.set("notif:nba-1:v1:u1", "1");   // written by the script before claims held a time
        assertFalse(delivery.deliver(msg("u1", 1)));
        assertEquals(List.of(), receivedOn("gw:A"));
        assertEquals("1", jedis.get("notif:nba-1:v1:u1"));
    }

    @Test
    void deliverAllKeepsOrderAndDedupsInsideTheBatch() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        delivery.deliverAll(List.of(msg("u1", 1), msg("u1", 2), msg("u1", 2), msg("u1", 3)));
        List<String> pushes = receivedOn("gw:A");
        assertEquals(3, pushes.size());
        assertEquals(3, jedis.keys("notif:*").size());
    }

    @Test
    void notificationInApiShape() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        NotificationMessage m = msg("u1", 1);
        m.setCreatedAt(1_791_300_000_000L);
        delivery.deliver(m);
        JsonObject payload = payloadOf(receivedOn("gw:A").get(0));
        assertEquals("notification", payload.get("type").getAsString());
        JsonObject n = payload.getAsJsonObject("notification");
        assertEquals("nba-1:v1", n.get("id").getAsString());
        assertEquals("2026-10-06T15:20:00Z", n.get("createdAt").getAsString());
    }

    @Test
    void counterPresentIncrementsAndPublishesAfter() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        jedis.set("unread:u1", "4");
        delivery.deliver(msg("u1", 1));
        List<String> got = receivedOn("gw:A");
        assertEquals(2, got.size(), got.toString());
        assertEquals("notification", payloadOf(got.get(0)).get("type").getAsString());
        JsonObject count = payloadOf(got.get(1));
        assertEquals("unread_count", count.get("type").getAsString());
        assertEquals(5, count.get("count").getAsInt());
        assertEquals("5", jedis.get("unread:u1"));
    }

    @Test
    void counterMissingStaysMissing() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        delivery.deliver(msg("u1", 1));
        assertFalse(jedis.exists("unread:u1"), "INCR on nothing would start at 1, not the real count");
        assertEquals(1, receivedOn("gw:A").size());
    }

    @Test
    void redeliveryCountsOnce() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        jedis.set("unread:u1", "0");
        delivery.deliver(msg("u1", 1));
        delivery.deliver(msg("u1", 1));
        assertEquals("1", jedis.get("unread:u1"));
        assertEquals(2, receivedOn("gw:A").size());
    }

    @Test
    void noBodyIsClaimedAndCountedButNotPushed() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        jedis.set("unread:u1", "0");
        GameEvent play = new GameEvent("nba-1", "nba-1:1", "SCORE_UPDATE", "LAL", 1234L, "LAL scores!",
            "LAL", "BOS", 2, 0, "IN_PROGRESS", "NBA", 10).withVersion(1);
        assertTrue(delivery.deliver(NotificationMessage.of(play, "u1")));
        assertTrue(jedis.exists("notif:nba-1:v1:u1"));
        assertEquals("1", jedis.get("unread:u1"));
        List<String> got = receivedOn("gw:A");
        assertEquals(1, got.size(), "only the count: a pre-3.3 message has no API body to push");
        assertEquals("unread_count", payloadOf(got.get(0)).get("type").getAsString());
    }

    private static JsonObject payloadOf(String received) {
        return JsonParser.parseString(received.substring(received.indexOf('|') + 1)).getAsJsonObject().getAsJsonObject("payload");
    }

    @Test
    void deliverAllReportsEachResult() {
        assertEquals(java.util.List.of(true, false, true),
            delivery.deliverAll(java.util.List.of(msg("u1", 1), msg("u1", 1), msg("u1", 2))));
    }

    @Test
    void gatewayMessageCarriesTraceparent() throws Exception {
        jedis.zadd("conn:u1", redisNow() + 90, "A");
        String tp = "00-5b8efff798038103d269b633813fc60c-eee19b7ec3c1b174-01";
        NotificationMessage traced = msg("u1", 1);
        traced.setTraceparent(tp);
        delivery.deliverAll(java.util.List.of(traced, msg("u1", 2)));
        java.util.List<String> got = receivedOn("gw:A");
        com.google.gson.JsonObject first = com.google.gson.JsonParser.parseString(got.get(0).substring("gw:A|".length())).getAsJsonObject();
        assertEquals(tp, first.get("traceparent").getAsString());
        String second = got.stream().filter(r -> r.contains("\"notification\"")).skip(1).findFirst().orElseThrow();
        assertFalse(com.google.gson.JsonParser.parseString(second.substring("gw:A|".length())).getAsJsonObject().has("traceparent"));
    }
}

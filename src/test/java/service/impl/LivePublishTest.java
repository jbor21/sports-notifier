package service.impl;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import service.ProcessResult;
import testsupport.TestPublisher;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** The accept script publishes every accepted play on live:<gameId>, in version order (Phase 3.4 spec, 7.1). */
class LivePublishTest {

    private JedisPool pool;
    private Jedis jedis;
    private Jedis listener;
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private final JedisPubSub sub = new JedisPubSub() {
        @Override public void onMessage(String channel, String message) { heard.add(message); }
        @Override public void onSubscribe(String channel, int n) { subscribed.countDown(); }
    };
    private final CountDownLatch subscribed = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws InterruptedException {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        listener = new Jedis("localhost", 6379);
        Thread t = new Thread(() -> listener.subscribe(sub, "live:g1"));
        t.setDaemon(true);
        t.start();
        assertTrue(subscribed.await(5, TimeUnit.SECONDS));
    }

    @AfterEach
    void tearDown() {
        sub.unsubscribe();
        listener.close();
        jedis.close();
        pool.close();
    }

    private static GameEvent play(String id, long seq, int home) {
        return new GameEvent("g1", id, "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", home, 0, "IN_PROGRESS", "NBA", seq);
    }

    private void awaitHeard(int n) throws InterruptedException {
        long until = System.currentTimeMillis() + 5000;
        while (heard.size() < n && System.currentTimeMillis() < until) Thread.sleep(20);
    }

    @Test
    void acceptedPlayIsPublished() throws InterruptedException {
        EventProcessingServiceImpl service = new EventProcessingServiceImpl(pool, new TestPublisher(true), System::currentTimeMillis);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(play("e1", 10, 2)));
        awaitHeard(1);
        assertEquals(1, heard.size());
        JsonObject m = JsonParser.parseString(heard.get(0)).getAsJsonObject();
        assertEquals(jedis.hget("game:g1", "version"), m.get("version").getAsString());
        assertEquals("g1", m.getAsJsonObject("play").get("gameId").getAsString());
        assertEquals(2, m.getAsJsonObject("play").get("homeScore").getAsInt());
    }

    @Test
    void staleAndPendingPublishNothing() throws InterruptedException {
        TestPublisher held = new TestPublisher(false);   // never confirms, so the outbox stays full
        EventProcessingServiceImpl service = new EventProcessingServiceImpl(pool, held, System::currentTimeMillis);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(play("e1", 10, 2)));
        assertEquals(ProcessResult.PENDING, service.processEvent(play("e2", 20, 4)));
        jedis.del("outbox:g1");
        assertEquals(ProcessResult.STALE, service.processEvent(play("e0", 5, 1)));
        Thread.sleep(300);
        assertEquals(1, heard.size(), "only the accepted play: " + heard);
    }

    @Test
    void tenAcceptsPublishInVersionOrder() throws InterruptedException {
        EventProcessingServiceImpl service = new EventProcessingServiceImpl(pool, new TestPublisher(true), System::currentTimeMillis);
        for (int i = 1; i <= 10; i++) assertEquals(ProcessResult.ACCEPTED, service.processEvent(play("e" + i, 10L * i, i)));
        awaitHeard(10);
        assertEquals(10, heard.size());
        long last = 0;
        for (String h : heard) {
            long v = JsonParser.parseString(h).getAsJsonObject().get("version").getAsLong();
            assertEquals(last == 0 ? v : last + 1, v);
            last = v;
        }
    }
}

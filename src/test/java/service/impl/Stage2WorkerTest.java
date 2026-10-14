package service.impl;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import telemetry.Traces;
import testsupport.TelemetryTestSupport;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import util.RedisKeys;
import redis.clients.jedis.JedisPubSub;
import com.google.gson.Gson;
import model.GameEvent;
import model.NotificationMessage;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.KafkaTestSupport;
import util.KafkaSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Stage 2: delivers each notifications message exactly once in effect, in order per user. */
class Stage2WorkerTest {

    private static final Gson GSON = new Gson();

    private JedisPool pool;
    private Jedis jedis;
    private String topic;
    private KafkaProducer<String, String> producer;
    private final List<Stage2Worker> workers = new ArrayList<>();

    /** Pushes this test's gateway received, in order: what a browser tab would have been sent. */
    private final List<String> pushes = new CopyOnWriteArrayList<>();
    private String gateway;
    private Jedis listener;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        topic = "notifications-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 48);
        producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        gateway = "test-gw-" + UUID.randomUUID();
        listener = new Jedis("localhost", 6379);
        CountDownLatch subscribed = new CountDownLatch(1);
        JedisPubSub sub = new JedisPubSub() {
            @Override public void onMessage(String channel, String message) { pushes.add(message); }
            @Override public void onSubscribe(String channel, int count) { subscribed.countDown(); }
        };
        new Thread(() -> listener.subscribe(sub, RedisKeys.GATEWAY_CHANNEL_PREFIX + gateway)).start();
        try {
            assertTrue(subscribed.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void tearDown() {
        listener.close();
        workers.forEach(Stage2Worker::close);
        producer.close();
        jedis.close();
        pool.close();
    }

    private Stage2Worker worker(String group, Delivery delivery) {
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group));
        consumer.subscribe(List.of(topic));
        Stage2Worker w = new Stage2Worker(consumer, delivery);
        workers.add(w);
        return w;
    }

    private static void pollUntilHandled(Stage2Worker w, int records) {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        int handled = 0;
        while (handled < records) {
            assertTrue(System.nanoTime() < deadline, "handled only " + handled + " of " + records);
            handled += w.pollOnce(Duration.ofMillis(200));
        }
    }

    private static NotificationMessage msg(String userId, long version) {
        GameEvent play = new GameEvent("nba-1", "nba-1:" + version, "SCORE_UPDATE", "LAL", 1234L, "play " + version,
            "LAL", "BOS", (int) version, 0, "IN_PROGRESS", "NBA", 10 + version).withVersion(version);
        return testsupport.TestMessages.of(play, userId);
    }

    private void send(NotificationMessage m) throws Exception {
        producer.send(new ProducerRecord<>(topic, m.getUserId(), GSON.toJson(m))).get();
    }

    /** What the user's gateway received, in order, as the text of each notification. */
    private List<String> pushedTo(String userId) throws InterruptedException {
        Thread.sleep(300);
        List<String> texts = new ArrayList<>();
        for (String raw : pushes) {
            com.google.gson.JsonObject m = com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
            if (userId.equals(m.get("userId").getAsString())) {
                texts.add(m.getAsJsonObject("payload").getAsJsonObject("notification").getAsJsonObject("headline").get("text").getAsString());
            }
        }
        return texts;
    }

    /** Every user in the test holds a socket on this test's own gateway, so its channel hears every push. */
    private void connect(String... userIds) {
        long expires = Long.parseLong(jedis.time().get(0)) + 300;
        for (String u : userIds) jedis.zadd("conn:" + u, expires, gateway);
    }

    @Test
    void crashMidBatchThenTakeoverDeliversEachOnceInOrder() throws Exception {
        // Spec 2.5 #6: a worker dies mid-batch without committing; the lane moves to another worker,
        // which re-reads from the last commit. The script's dedup key turns the re-reads into no-ops.
        int users = 20, perUser = 10;
        for (int u = 0; u < users; u++) connect("u" + u);
        for (long v = 1; v <= perUser; v++) {
            for (int u = 0; u < users; u++) send(msg("u" + u, v));
        }
        String group = "s2-" + UUID.randomUUID();
        Delivery diesHalfway = new Delivery(pool) {
            @Override public List<Boolean> deliverAll(List<NotificationMessage> batch) {
                super.deliverAll(batch.subList(0, batch.size() / 2));
                throw new IllegalStateException("crash");
            }
        };
        Stage2Worker a = worker(group, diesHalfway);
        assertThrows(IllegalStateException.class, () -> pollUntilHandled(a, users * perUser));
        a.close();
        pollUntilHandled(worker(group, new Delivery(pool)), 1);
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (jedis.keys("notif:*").size() < users * perUser && System.nanoTime() < deadline) {
            workers.get(1).pollOnce(Duration.ofMillis(200));
        }
        List<String> expected = new ArrayList<>();
        for (long v = 1; v <= perUser; v++) expected.add("play " + v);
        for (int u = 0; u < users; u++) assertEquals(expected, pushedTo("u" + u), "user u" + u);
    }

    @Test
    void poisonRecordIsSkippedAndCommitted() throws Exception {
        connect("u1");
        producer.send(new ProducerRecord<>(topic, "u1", "{not json")).get();
        send(msg("u1", 1));
        pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 2);
        assertEquals(List.of("play 1"), pushedTo("u1"));
    }

    @Test
    void newGroupDeliversEarlierMessages() throws Exception {
        connect("u1");
        send(msg("u1", 1));
        pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 1);
        assertEquals(List.of("play 1"), pushedTo("u1"));
    }

    private void send(NotificationMessage m, Span parent) throws Exception {
        ProducerRecord<String, String> r = new ProducerRecord<>(topic, m.getUserId(), GSON.toJson(m));
        Traces.inject(parent, r.headers());
        producer.send(r).get();
    }

    @Test
    void deliverSpanPerMessage() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            connect("u1", "u2");
            Span a = Traces.start("fanout", SpanKind.CONSUMER, Context.root());
            Span b = Traces.start("fanout", SpanKind.CONSUMER, Context.root());
            a.end();
            b.end();
            send(msg("u1", 1), a);
            send(msg("u2", 1), b);
            pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 2);
            var delivers = otel.spans("deliver");
            assertEquals(2, delivers.size());
            for (Span parent : List.of(a, b)) {
                var d = delivers.stream().filter(sp -> sp.getParentSpanId().equals(parent.getSpanContext().getSpanId())).findFirst().orElseThrow();
                assertEquals(SpanKind.CONSUMER, d.getKind());
                assertEquals("delivered", d.getAttributes().get(AttributeKey.stringKey("deliver.result")));
                Thread.sleep(300);
                assertTrue(pushes.stream().anyMatch(p -> p.contains(d.getSpanId())), "the push carries its deliver span");
            }
            assertEquals(2, otel.latencyCount("stage2"));
            assertEquals(2, otel.counter("deliveries", "result", "delivered"));
        }
    }

    @Test
    void redeliveryIsDuplicate() throws Exception {
        try (TelemetryTestSupport otel = TelemetryTestSupport.install()) {
            connect("u1");
            Span a = Traces.start("fanout", SpanKind.CONSUMER, Context.root());
            a.end();
            send(msg("u1", 1), a);
            send(msg("u1", 1), a);   // Stage 1 sent it twice
            pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 2);
            var results = otel.spans("deliver").stream()
                .map(sp -> sp.getAttributes().get(AttributeKey.stringKey("deliver.result"))).sorted().toList();
            assertEquals(List.of("delivered", "duplicate"), results);
            assertEquals(1, otel.latencyCount("stage2"));
        }
    }
}

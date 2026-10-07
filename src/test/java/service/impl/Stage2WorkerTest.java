package service.impl;

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

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        topic = "notifications-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 48);
        producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
    }

    @AfterEach
    void tearDown() {
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
        return NotificationMessage.of(play, userId);
    }

    private void send(NotificationMessage m) throws Exception {
        producer.send(new ProducerRecord<>(topic, m.getUserId(), GSON.toJson(m))).get();
    }

    /** The user's history, oldest first, as the text of each entry. */
    private List<String> historyOf(String userId) {
        List<String> newestFirst = jedis.lrange("notifications:" + userId, 0, -1);
        List<String> texts = new ArrayList<>();
        for (String json : newestFirst) texts.add(GSON.fromJson(json, model.Notification.class).getMessage());
        Collections.reverse(texts);
        return texts;
    }

    @Test
    void crashMidBatchThenTakeoverDeliversEachOnceInOrder() throws Exception {
        // Spec 2.5 #6: a worker dies mid-batch without committing; the lane moves to another worker,
        // which re-reads from the last commit. The script's dedup key turns the re-reads into no-ops.
        int users = 20, perUser = 10;
        for (long v = 1; v <= perUser; v++) {
            for (int u = 0; u < users; u++) send(msg("u" + u, v));
        }
        String group = "s2-" + UUID.randomUUID();
        Delivery diesHalfway = new Delivery(pool) {
            @Override public void deliverAll(List<NotificationMessage> batch) {
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
        for (int u = 0; u < users; u++) assertEquals(expected, historyOf("u" + u), "user u" + u);
    }

    @Test
    void poisonRecordIsSkippedAndCommitted() throws Exception {
        producer.send(new ProducerRecord<>(topic, "u1", "{not json")).get();
        send(msg("u1", 1));
        pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 2);
        assertEquals(List.of("play 1"), historyOf("u1"));
    }

    @Test
    void newGroupDeliversEarlierMessages() throws Exception {
        send(msg("u1", 1));
        pollUntilHandled(worker("s2-" + UUID.randomUUID(), new Delivery(pool)), 1);
        assertEquals(List.of("play 1"), historyOf("u1"));
    }
}

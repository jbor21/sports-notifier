package service.impl;

import model.GameEvent;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.PlayPublisher;
import service.ProcessResult;
import testsupport.TestPublisher;
import testsupport.KafkaTestSupport;
import util.GameEventParser;
import util.KafkaSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/** The retry loop: whatever is still in the outbox gets sent, in order (Phase 3.1 spec, section 1.7). */
class OutboxRelayTest {

    private JedisPool pool;
    private Jedis jedis;
    private String topic;
    private KafkaPlayPublisher kafka;
    private Outbox outbox;
    private final AtomicLong now = new AtomicLong(100_000);

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        topic = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 12);
        kafka = new KafkaPlayPublisher(new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap())), topic);
        outbox = new Outbox(pool);
    }

    @AfterEach
    void tearDown() {
        kafka.close();
        jedis.close();
        pool.close();
    }

    static GameEvent play(String gameId, long seq, int home) {
        return new GameEvent(gameId, gameId + ":" + seq, "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", home, 0, "IN_PROGRESS", "NBA", seq);
    }

    /** The versions of this game's plays on the topic, in log order. */
    static List<Long> versionsOnTopic(String topic, String gameId, Duration wait) {
        List<Long> versions = new ArrayList<>();
        for (ConsumerRecord<String, String> r : KafkaTestSupport.readAll(topic, wait)) {
            GameEvent p = GameEventParser.parse(r.value());
            if (gameId.equals(p.getGameId())) versions.add(p.getVersion());
        }
        return versions;
    }

    private OutboxRelay relay(PlayPublisher publisher, long minAgeMillis) {
        return new OutboxRelay(outbox, publisher, now::get, minAgeMillis);
    }

    @Test
    void crashAfterAcceptThenRelayKeepsOrder() throws Exception {
        // Pod A accepts seq 10 and "crashes": its send never completes, so the play stays in the outbox.
        new EventProcessingServiceImpl(pool, new TestPublisher(false), now::get).processEvent(play("g1", 10, 1));
        // Pod B is up. A newer play waits behind the stuck one instead of overtaking it.
        EventProcessingServiceImpl b = new EventProcessingServiceImpl(pool, kafka, now::get);
        assertEquals(ProcessResult.PENDING, b.processEvent(play("g1", 11, 2)));
        relay(kafka, 0).runOnce().get(10, TimeUnit.SECONDS);
        assertEquals(ProcessResult.ACCEPTED, b.processEvent(play("g1", 11, 2)));
        assertEquals(List.of(1L, 2L), versionsOnTopic(topic, "g1", Duration.ofSeconds(2)));
    }

    @Test
    void relaySendsLeftoverAndClears() throws Exception {
        new EventProcessingServiceImpl(pool, new TestPublisher(false), now::get).processEvent(play("g1", 10, 1));
        relay(kafka, 0).runOnce().get(10, TimeUnit.SECONDS);
        assertFalse(jedis.exists("outbox:g1"));
        assertNull(jedis.zscore("outbox:pending", "g1"));
        assertEquals(List.of(1L), versionsOnTopic(topic, "g1", Duration.ofSeconds(2)));
    }

    @Test
    void relayIgnoresFreshEntries() throws Exception {
        new EventProcessingServiceImpl(pool, new TestPublisher(false), now::get).processEvent(play("g1", 10, 1));
        TestPublisher recorder = new TestPublisher(true);
        now.addAndGet(4_999);
        relay(recorder, 5_000).runOnce().get(10, TimeUnit.SECONDS);
        assertTrue(recorder.plays.isEmpty(), "a play accepted under 5 s ago is probably still being sent normally");
        now.addAndGet(1);
        relay(recorder, 5_000).runOnce().get(10, TimeUnit.SECONDS);
        assertEquals(1, recorder.plays.size());
    }

    @Test
    void relayDoesNotResendWhileItsSendIsInFlight() throws Exception {
        // Review finding I3: during a slow or down Kafka the loop re-sent every waiting play every
        // second, queueing about one copy per second per game. One send at a time per play instead.
        new EventProcessingServiceImpl(pool, new TestPublisher(false), now::get).processEvent(play("g1", 10, 1));
        TestPublisher stuck = new TestPublisher(false);
        OutboxRelay relay = relay(stuck, 0);
        relay.runOnce();
        relay.runOnce();
        relay.runOnce();
        assertEquals(1, stuck.plays.size(), "still in flight: not sent again");
        stuck.pending.get(0).completeExceptionally(new RuntimeException("delivery timeout"));
        relay.runOnce();
        assertEquals(2, stuck.plays.size(), "the send failed, so the next run tries again");
    }

    @Test
    void twoRelaysNeverReorder() throws Exception {
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, kafka, now::get);
        new EventProcessingServiceImpl(pool, new TestPublisher(false), now::get).processEvent(play("g1", 10, 1));
        CompletableFuture<Void> first = relay(kafka, 0).runOnce();
        CompletableFuture<Void> second = relay(kafka, 0).runOnce();
        CompletableFuture.allOf(first, second).get(10, TimeUnit.SECONDS);
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(play("g1", 11, 2)));
        List<Long> versions = versionsOnTopic(topic, "g1", Duration.ofSeconds(2));
        assertTrue(versions.size() == 2 || versions.size() == 3, "v1 once or twice, then v2: " + versions);
        for (int i = 1; i < versions.size(); i++) assertTrue(versions.get(i) >= versions.get(i - 1), versions.toString());
        assertEquals(2L, versions.get(versions.size() - 1));
    }

    @Test
    void slowSendThenRelayClearsOnce() throws Exception {
        // Review Focus 4: Kafka is slow, not down. The first send reaches the log but its confirmation
        // is late; the retry loop sends the same play again. One duplicate (same version), never a reorder.
        CompletableFuture<Void> lateConfirmation = new CompletableFuture<>();
        PlayPublisher slow = p -> kafka.publish(p).thenCompose(ok -> lateConfirmation);
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, slow, now::get);
        eps.processEvent(play("g1", 10, 1));
        relay(kafka, 0).runOnce().get(10, TimeUnit.SECONDS);
        assertFalse(jedis.exists("outbox:g1"), "the relay's confirmed send clears the slot");
        lateConfirmation.complete(null);                         // the first send's confirmation arrives
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(play("g1", 11, 2)));
        List<Long> versions = versionsOnTopic(topic, "g1", Duration.ofSeconds(2));
        assertEquals(List.of(1L, 1L, 2L), versions);
    }
}

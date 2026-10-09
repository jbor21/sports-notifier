package service.impl;

import catalog.FollowKind;
import model.GameEvent;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.KafkaTestSupport;
import util.GameEventParser;
import util.KafkaSettings;
import util.LoopHealth;
import util.RedisKeys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * While {@code sub:built} is missing the sets may be empty, so Stage 1 waits instead of handling
 * plays against them: paused, not stopped. It keeps polling and beating, so a long rebuild neither
 * fails /health nor gets it thrown out of the group (spec section 6).
 */
class Stage1WaitsForSetsTest {

    private JedisPool pool;
    private Jedis jedis;
    private String gameEvents;
    private String notifications;
    private String group;
    private final List<Stage1Worker> workers = new ArrayList<>();
    private final List<Thread> loops = new ArrayList<>();

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        String id = UUID.randomUUID().toString();
        gameEvents = "game-events-" + id;
        notifications = "notifications-" + id;
        group = "s1-wait-" + id;
        KafkaTestSupport.createTopic(gameEvents, 12);
        KafkaTestSupport.createTopic(notifications, 48);
        jedis.sadd(FollowSets.key(FollowKind.TEAM, "nhl-bos", "GOAL"), "bos-fan");
        jedis.sadd(FollowSets.key(FollowKind.TEAM, "nhl-bos", "ANY"), "bos-fan");
    }

    @AfterEach
    void tearDown() throws Exception {
        workers.forEach(Stage1Worker::stop);
        for (Thread t : loops) t.join(10_000);
        jedis.close();
        pool.close();
    }

    /** A worker running its real loop on its own thread, as Stage1WorkerMain runs it. */
    private Stage1Worker start(LoopHealth health, AtomicInteger revocations) {
        Properties p = KafkaSettings.consumer(KafkaTestSupport.bootstrap(), group);
        p.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, "7000");
        p.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, "6000");
        p.put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, "1000");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(p);
        consumer.subscribe(List.of(gameEvents), new ConsumerRebalanceListener() {
            @Override public void onPartitionsRevoked(Collection<TopicPartition> parts) { if (!parts.isEmpty()) revocations.incrementAndGet(); }
            @Override public void onPartitionsAssigned(Collection<TopicPartition> parts) {}
        });
        KafkaProducer<String, String> producer = new KafkaProducer<>(KafkaSettings.fanoutProducer(KafkaTestSupport.bootstrap()));
        alerts.GameLabels labels = new alerts.GameLabels(catalog.Teams.load());
        Stage1Worker w = new Stage1Worker(new DetectorMemory(pool), alerts.Detectors.standard(labels),
                new MomentFanout(pool, new AlertAudience(), labels), consumer, producer, notifications,
                notifications + "-dlq", () -> { try (Jedis j = pool.getResource()) { return FollowSets.isBuilt(j); } });
        Thread loop = new Thread(() -> { try { w.run(health); } finally { w.close(); } }, "stage1-test");
        health.attach(loop);
        loop.start();
        workers.add(w);
        loops.add(loop);
        return w;
    }

    /** A Bruins goal at version 2, after a 0-0 baseline at version 1 that Stage 1 has already handled. */
    private void sendGoal() throws Exception {
        GameEvent baseline = new GameEvent("nhl-1", "nhl-1:1", "GAME_SYNC", "TOR", 1234L, "",
                "TOR", "BOS", 0, 0, "IN_PROGRESS", "NHL", 10).withVersion(1);
        alerts.GameLabels labels = new alerts.GameLabels(catalog.Teams.load());
        new DetectorMemory(pool).writeIfHigher("nhl-1", 1,
                alerts.Detectors.standard(labels).run(null, alerts.Play.of(baseline)).after());
        GameEvent play = new GameEvent("nhl-1", "nhl-1:2", "SCORE_UPDATE", "BOS", 1234L, "BOS scores!",
                "TOR", "BOS", 0, 1, "IN_PROGRESS", "NHL", 11).withVersion(2);
        try (KafkaProducer<String, String> p = new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap()))) {
            p.send(new ProducerRecord<>(gameEvents, play.getGameId(), GameEventParser.toJson(play))).get();
        }
    }

    private long committedTotal() throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestSupport.bootstrap());
        // Bounded, so a broker that stops answering fails the test in seconds instead of hanging it.
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, "5000");
        try (Admin admin = Admin.create(p)) {
            Map<TopicPartition, OffsetAndMetadata> offsets =
                    admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(15, TimeUnit.SECONDS);
            return offsets.values().stream().filter(o -> o != null).mapToLong(OffsetAndMetadata::offset).sum();
        }
    }

    private int notificationCount(Duration wait) {
        return KafkaTestSupport.readAll(notifications, wait).size();
    }

    @Test
    void waitsPausedAndStaysHealthyInTheGroup() throws Exception {
        LoopHealth health = new LoopHealth(System::currentTimeMillis, 3000);
        AtomicInteger revocations = new AtomicInteger();
        Stage1Worker w = start(health, revocations);
        sendGoal();

        // Longer than the 3 s health window and twice max.poll.interval.ms (7 s).
        long until = System.currentTimeMillis() + 15_000;
        boolean sawWaiting = false;
        while (System.currentTimeMillis() < until) {
            sawWaiting |= w.isWaitingForSets();
            assertTrue(health.isHealthy(), "the loop keeps beating while it waits");
            Thread.sleep(500);
        }
        assertTrue(sawWaiting);
        assertTrue(w.isWaitingForSets());
        assertEquals(0, notificationCount(Duration.ofSeconds(1)), "nothing built from empty sets");
        assertEquals(0, committedTotal(), "the play is not committed while waiting");
        assertEquals(0, revocations.get(), "no rebalance: still polling, still in the group");

        FollowSets.markBuilt(jedis, "test");
        long deadline = System.currentTimeMillis() + 10_000;
        while (committedTotal() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(200);
        assertEquals(1, committedTotal());
        assertFalse(w.isWaitingForSets());
        assertEquals(1, notificationCount(Duration.ofSeconds(2)), "delivered once the sets are back, exactly once");
    }

    @Test
    void lanesGainedInARebalanceWaitToo() throws Exception {
        AtomicInteger revocations = new AtomicInteger();
        start(new LoopHealth(System::currentTimeMillis, 3000), revocations);
        sendGoal();
        Thread.sleep(3000);
        start(new LoopHealth(System::currentTimeMillis, 3000), revocations);   // a second worker joins
        Thread.sleep(12_000);
        assertEquals(0, notificationCount(Duration.ofSeconds(1)), "neither worker handled the play");
        assertEquals(0, committedTotal());

        FollowSets.markBuilt(jedis, "test");
        long deadline = System.currentTimeMillis() + 15_000;
        while (committedTotal() < 1 && System.currentTimeMillis() < deadline) Thread.sleep(200);
        assertEquals(1, notificationCount(Duration.ofSeconds(2)));
    }
}

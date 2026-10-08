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
import service.ProcessResult;
import testsupport.KafkaTestSupport;
import util.GameEventParser;
import util.KafkaSettings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Event Processing's producer: accepted plays reach game-events, keyed by game, in version order. */
class KafkaPlayPublisherTest {

    private JedisPool pool;
    private Jedis jedis;
    private String topic;
    private KafkaPlayPublisher kafka;
    private EventProcessingServiceImpl eps;

    @BeforeEach
    void setUp() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(60);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        topic = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 12);
        kafka = new KafkaPlayPublisher(new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap())), topic);
        eps = new EventProcessingServiceImpl(pool, kafka, System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        kafka.close();
        jedis.close();
        pool.close();
    }

    @Test
    void playIsKeyedByGameAndCarriesVersion() {
        eps.processEvent(OutboxRelayTest.play("g1", 10, 1));
        List<ConsumerRecord<String, String>> records = KafkaTestSupport.readAll(topic, Duration.ofSeconds(2));
        assertEquals(1, records.size());
        assertEquals("g1", records.get(0).key());
        // The version the script assigned (the counter starts from the clock, not 0).
        assertEquals(Long.parseLong(jedis.hget("game:g1", "version")), GameEventParser.parse(records.get(0).value()).getVersion());
    }

    @Test
    void gameSyncIsProduced() {
        // Every accepted play goes to the log: the live score can be rebuilt from it, and 3.3's
        // detectors need halftime and delays, which arrive as GAME_SYNC.
        GameEvent sync = new GameEvent("g1", "g1:10", "GAME_SYNC", "LAL", 1000L, "LAL vs BOS: HALFTIME",
            "LAL", "BOS", 50, 48, "HALFTIME", "NBA", 10);
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(sync));
        List<ConsumerRecord<String, String>> records = KafkaTestSupport.readAll(topic, Duration.ofSeconds(2));
        assertEquals(1, records.size());
        assertEquals("GAME_SYNC", GameEventParser.parse(records.get(0).value()).getEventType());
    }

    @Test
    void burstLandsInVersionOrder() throws Exception {
        int events = 50;
        ExecutorService threads = Executors.newFixedThreadPool(events);
        AtomicInteger accepted = new AtomicInteger();
        try {
            CountDownLatch gate = new CountDownLatch(1);
            List<Future<?>> done = new ArrayList<>();
            for (int i = 1; i <= events; i++) {
                final int seq = i;
                done.add(threads.submit(() -> {
                    gate.await();
                    ProcessResult r;
                    while ((r = eps.processEvent(OutboxRelayTest.play("burst", seq, seq))) == ProcessResult.PENDING) Thread.sleep(1);
                    if (r == ProcessResult.ACCEPTED) accepted.incrementAndGet();
                    return null;
                }));
            }
            gate.countDown();
            for (Future<?> f : done) f.get(60, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
        assertEquals("50", jedis.hget("game:burst", "homeScore"));
        List<GameEvent> plays = new ArrayList<>();
        for (ConsumerRecord<String, String> r : KafkaTestSupport.readAll(topic, Duration.ofSeconds(3))) {
            plays.add(GameEventParser.parse(r.value()));
        }
        assertEquals(accepted.get(), plays.size());
        long first = plays.get(0).getVersion();
        for (int k = 0; k < plays.size(); k++) {
            assertEquals(first + k, plays.get(k).getVersion());
            if (k > 0) assertTrue(plays.get(k).getSeq() > plays.get(k - 1).getSeq());
        }
    }
}

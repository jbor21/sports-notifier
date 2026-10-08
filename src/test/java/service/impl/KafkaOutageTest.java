package service.impl;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;
import testsupport.KafkaTestSupport;
import util.KafkaSettings;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka goes down mid-game (Phase 3.1 spec, section 1.8): the first play after the outage is
 * accepted and waits in the outbox, later plays are "pending" and the live score freezes, nothing
 * accepted is lost, and when Kafka returns the waiting play goes first.
 */
class KafkaOutageTest {

    private JedisPool pool;
    private Jedis jedis;
    private String topic;
    private KafkaPlayPublisher kafka;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        topic = "game-events-" + UUID.randomUUID();
        KafkaTestSupport.createTopic(topic, 12);
        kafka = new KafkaPlayPublisher(new KafkaProducer<>(KafkaSettings.ingestProducer(KafkaTestSupport.bootstrap())), topic);
    }

    @AfterEach
    void tearDown() {
        KafkaTestSupport.unpauseIfPaused();   // never leave the shared broker frozen; closing a producer mid-request to a paused broker hangs
        kafka.close();
        jedis.close();
        pool.close();
    }

    private void awaitOutboxEmpty(String gameId) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (jedis.exists("outbox:" + gameId)) {
            assertTrue(System.nanoTime() < deadline, "outbox:" + gameId + " never cleared");
            Thread.sleep(50);
        }
    }

    @Test
    void outageMakesGamePendingAndRecoversInOrder() throws Exception {
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, kafka, System::currentTimeMillis);
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(OutboxRelayTest.play("g1", 10, 1)));
        awaitOutboxEmpty("g1");

        KafkaTestSupport.pause();
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(OutboxRelayTest.play("g1", 11, 2)));
        assertEquals(ProcessResult.PENDING, eps.processEvent(OutboxRelayTest.play("g1", 12, 3)));
        assertEquals("2", jedis.hget("game:g1", "homeScore"), "the live score freezes at the waiting play");
        assertTrue(jedis.exists("outbox:g1"), "the accepted play is safe in the outbox");

        KafkaTestSupport.unpause();
        new OutboxRelay(new Outbox(pool), kafka, () -> Long.MAX_VALUE / 2, 0).runOnce().get(60, TimeUnit.SECONDS);
        awaitOutboxEmpty("g1");
        // The next poll carries everything that happened during the outage as one play.
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(OutboxRelayTest.play("g1", 12, 3)));
        awaitOutboxEmpty("g1");

        List<Long> versions = OutboxRelayTest.fromOne(OutboxRelayTest.versionsOnTopic(topic, "g1", Duration.ofSeconds(2)));
        assertTrue(versions.containsAll(List.of(1L, 2L, 3L)), versions.toString());
        for (int i = 1; i < versions.size(); i++) assertTrue(versions.get(i) >= versions.get(i - 1), versions.toString());
        assertEquals(3L, versions.get(versions.size() - 1));
    }
}

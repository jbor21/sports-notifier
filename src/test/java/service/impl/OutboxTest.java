package service.impl;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.PlayPublisher;
import testsupport.TestPublisher;
import service.ProcessResult;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** The outbox: an accepted play waits in Redis until the log confirms it (spec 3.1, section 1.2). */
class OutboxTest {

    private JedisPool pool;
    private Jedis jedis;
    private Outbox outbox;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        outbox = new Outbox(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private static GameEvent play(String id, long seq, int home) {
        return new GameEvent("g1", id, "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", home, 0, "IN_PROGRESS", "NBA", seq);
    }

    private EventProcessingServiceImpl service(PlayPublisher publisher) {
        return new EventProcessingServiceImpl(pool, publisher, () -> 5_000L);
    }

    @Test
    void newerPlayIsPendingWhileOlderIsUnsent() {
        EventProcessingServiceImpl eps = service(new TestPublisher(false));
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(play("e10", 10, 2)));
        assertEquals(ProcessResult.PENDING, eps.processEvent(play("e11", 11, 4)));
        assertEquals("2", jedis.hget("game:g1", "homeScore"));
        assertEquals("1", jedis.hget("game:g1", "version"));
        assertFalse(jedis.exists("processed:e11"), "a pending play must not burn its id");
    }

    @Test
    void acceptWritesPlayWithVersionToOutbox() {
        service(new TestPublisher(false)).processEvent(play("e10", 10, 2));
        GameEvent waiting = outbox.play("g1").orElseThrow();
        assertEquals(1L, waiting.getVersion());
        assertEquals("e10", waiting.getEventId());
        assertEquals(10L, waiting.getSeq());
        assertEquals(5_000.0, jedis.zscore("outbox:pending", "g1"));
    }

    @Test
    void confirmedPublishClearsOutbox() {
        TestPublisher pub = new TestPublisher(true);
        service(pub).processEvent(play("e10", 10, 2));
        assertEquals(1L, pub.plays.get(0).getVersion());
        assertFalse(jedis.exists("outbox:g1"));
        assertNull(jedis.zscore("outbox:pending", "g1"));
    }

    @Test
    void clearWithStaleVersionLeavesNewerEntry() {
        TestPublisher pub = new TestPublisher(false);
        EventProcessingServiceImpl eps = service(pub);
        eps.processEvent(play("e10", 10, 2));
        assertTrue(outbox.clear("g1", 1));
        eps.processEvent(play("e11", 11, 4));                       // version 2 now waits
        assertFalse(outbox.clear("g1", 1), "a late confirmation of v1 must not clear v2");
        assertTrue(jedis.exists("outbox:g1"));
        assertTrue(outbox.clear("g1", 2));
        assertFalse(jedis.exists("outbox:g1"));
        assertNull(jedis.zscore("outbox:pending", "g1"));
    }

    @Test
    void sixteenDigitSeqSurvivesTheOutbox() {
        // Simulator seqs are 16 digits; the outbox must not round them (cjson would print 1.79e+15).
        service(new TestPublisher(false)).processEvent(play("sim", 1_790_000_000_000_005L, 5));
        assertEquals(1_790_000_000_000_005L, outbox.play("g1").orElseThrow().getSeq());
    }

    @Test
    void gamesWaitingSinceReturnsOnlyOldEnoughEntries() {
        new EventProcessingServiceImpl(pool, new TestPublisher(false), () -> 1_000L).processEvent(play("a", 10, 1));
        GameEvent other = new GameEvent("g2", "b", "SCORE_UPDATE", "LAL", 1000L, "desc",
            "LAL", "BOS", 1, 0, "IN_PROGRESS", "NBA", 10);
        new EventProcessingServiceImpl(pool, new TestPublisher(false), () -> 9_000L).processEvent(other);
        assertEquals(List.of("g1"), outbox.gamesWaitingSince(4_000L));
    }
}

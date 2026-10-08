package service.impl;

import feed.Seq;
import model.GameEvent;
import model.GameSnapshot;
import model.GameSnapshot.Half;
import model.GameSnapshot.Phase;
import model.GameSnapshot.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;
import testsupport.TestPublisher;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The two heals (Phase 3.1 spec, section 1.4). Recorded on 2026-10-02: an NHL game showed 4:01 left
 * in the 2nd, then 11:45. The 4:01 snapshot's seq became the bar, and every real snapshot after it
 * was "stale" until the game caught up; a goal in those minutes would have sent no alert.
 */
class EventProcessingHealTest {

    private JedisPool pool;
    private Jedis jedis;
    private final AtomicLong now = new AtomicLong(0);
    private EventProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new EventProcessingServiceImpl(pool, new TestPublisher(true), now::get);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** An NHL play in the given period with this much clock left, built with the real Seq. */
    private static GameEvent nhl(int period, int clockTenths, int home, int away) {
        GameSnapshot s = new GameSnapshot("nhl-1", "NHL", "CAR", "WSH", home, away,
            Phase.LIVE, period, Half.TOP, clockTenths, 0, Stage.IN_PLAY, "IN_PROGRESS", 0L);
        long seq = Seq.of(s);
        return new GameEvent("nhl-1", "nhl-1:" + seq, "SCORE_UPDATE", "CAR", 0L, "desc",
            "CAR", "WSH", home, away, "IN_PROGRESS", "NHL", seq);
    }

    private static final int GLITCH = 2410;   // 4:01 left, ahead of reality
    private static final int REAL = 7050;     // 11:45 left

    @Test
    void goalHealAcceptsMorePointsInSamePeriodAndLowersTheBar() {
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(nhl(2, GLITCH, 0, 4)));
        GameEvent goal = nhl(2, REAL, 0, 5);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(goal));
        assertEquals(String.valueOf(goal.getSeq()), jedis.hget("game:nhl-1", "lastSeq"));
        assertEquals("5", jedis.hget("game:nhl-1", "awayScore"));
        // The bar is now the real clock, so the next real snapshot is accepted normally.
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(nhl(2, REAL - 100, 0, 5)));
    }

    @Test
    void goalHealDoesNotAcceptSameScoreOlder() {
        service.processEvent(nhl(2, GLITCH, 0, 4));
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, REAL, 0, 4)));
    }

    @Test
    void goalHealNeedsTheSamePeriod() {
        service.processEvent(nhl(3, 12000, 0, 4));
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, REAL, 0, 5)));
        assertEquals("4", jedis.hget("game:nhl-1", "awayScore"));
    }

    @Test
    void noScoreHealAtTenSeconds() {
        service.processEvent(nhl(2, GLITCH, 0, 4));
        now.set(1_000);
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, REAL, 0, 4)));
        now.set(10_999);
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, REAL - 50, 0, 4)));
        now.set(11_000);
        GameEvent healed = nhl(2, REAL - 100, 0, 4);
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(healed));
        assertEquals(String.valueOf(healed.getSeq()), jedis.hget("game:nhl-1", "lastSeq"));
    }

    /** Bruins at Jets, 2nd period: ESPN showed 0:00 for 90 s mid-period, then 1:20 (nhl-401891831). */
    @Test
    void bruinsZeroZeroGlitchHealsInTenSeconds() {
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(nhl(2, 2350, 2, 2)));   // 3:55
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(nhl(2, 0, 2, 2)));      // 0:00, the glitch
        now.set(100_000);
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, 800, 2, 2)));       // 1:20
        now.set(105_000);
        assertEquals(ProcessResult.STALE, service.processEvent(nhl(2, 750, 2, 2)));       // 1:15
        now.set(110_000);
        GameEvent healed = nhl(2, 700, 2, 2);                                              // 1:10
        assertEquals(ProcessResult.ACCEPTED, service.processEvent(healed));
        assertEquals(String.valueOf(healed.getSeq()), jedis.hget("game:nhl-1", "lastSeq"));
    }

    @Test
    void anyAcceptClearsStaleSince() {
        service.processEvent(nhl(2, GLITCH, 0, 4));
        service.processEvent(nhl(2, REAL, 0, 4));                  // starts the 10-second timer
        assertNotNull(jedis.hget("game:nhl-1", "staleSince"));
        service.processEvent(nhl(2, GLITCH - 100, 0, 4));          // genuinely newer
        assertNull(jedis.hget("game:nhl-1", "staleSince"));
    }

    @Test
    void versionCountsAcceptsOnly() {
        TestPublisher held = new TestPublisher(false);
        EventProcessingServiceImpl eps = new EventProcessingServiceImpl(pool, held, now::get);
        GameEvent glitch = nhl(2, GLITCH, 0, 4);
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(glitch));
        assertEquals(ProcessResult.DUPLICATE, eps.processEvent(glitch));
        assertEquals(ProcessResult.PENDING, eps.processEvent(nhl(2, GLITCH - 10, 0, 4)));
        assertEquals("1", jedis.hget("game:nhl-1", "version"));
        new Outbox(pool).clear("nhl-1", 1);
        assertEquals(ProcessResult.STALE, eps.processEvent(nhl(2, REAL, 0, 4)));
        assertEquals("1", jedis.hget("game:nhl-1", "version"));
        assertEquals(ProcessResult.ACCEPTED, eps.processEvent(nhl(2, REAL, 0, 5)));   // goal heal
        assertEquals("2", jedis.hget("game:nhl-1", "version"));
    }

    @Test
    void healsIgnoreSimulatorSeqs() {
        // The simulator's seq is runStartMillis x 1000 + n: it orders runs, not positions in a game.
        GameEvent newerRun = new GameEvent("sim-1", "sim-new", "SCORE_UPDATE", "LAL", 0L, "desc",
            "LAL", "BOS", 5, 0, "IN_PROGRESS", "NBA", 1_790_000_000_000_005L);
        GameEvent olderRun = new GameEvent("sim-1", "sim-old", "SCORE_UPDATE", "LAL", 0L, "desc",
            "LAL", "BOS", 9, 0, "IN_PROGRESS", "NBA", 1_789_999_999_000_009L);
        service.processEvent(newerRun);
        assertEquals(ProcessResult.STALE, service.processEvent(olderRun));
        assertEquals("1790000000000005", jedis.hget("game:sim-1", "lastSeq"));
        now.set(60_000);
        GameEvent olderSameScore = new GameEvent("sim-1", "sim-old2", "SCORE_UPDATE", "LAL", 0L, "desc",
            "LAL", "BOS", 5, 0, "IN_PROGRESS", "NBA", 1_789_999_999_000_005L);
        assertEquals(ProcessResult.STALE, service.processEvent(olderSameScore));
        assertEquals(ProcessResult.STALE, service.processEvent(olderSameScore));
        assertEquals("1790000000000005", jedis.hget("game:sim-1", "lastSeq"));
    }
}

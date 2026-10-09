package simulator;

import feed.EventSink;
import feed.Poller;
import feed.RecordedScoreFeed;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;
import service.impl.EventProcessingServiceImpl;
import testsupport.TestPublisher;
import util.RedisKeys;

import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/** A replay set through the real poller and accept script (Phase 4.1 spec, 2.3–2.4). */
class ReplayTest {

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl eps;
    private List<RecordedScoreFeed.Line> lines;
    private Replay replay;

    @BeforeEach
    void setUp() throws Exception {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        eps = new EventProcessingServiceImpl(pool, new TestPublisher(true), System::currentTimeMillis);
        try (InputStream in = getClass().getResourceAsStream("/replay/tiny.jsonl")) {
            lines = RecordedScoreFeed.read(in);
        }
    }

    @AfterEach
    void tearDown() {
        if (replay != null) replay.stop();
        jedis.close();
        pool.close();
    }

    /** POST /events, called directly; plays of games named in {@code drop} vanish on the way. */
    private EventSink sink(String drop) {
        return event -> {
            if (drop != null && event.getGameId().equals(drop)) return new EventSink.PostResult(200, "accepted");
            ProcessResult r = eps.processEvent(event);
            return new EventSink.PostResult(r == ProcessResult.INVALID ? 400 : 200, r.name().toLowerCase(Locale.ROOT));
        };
    }

    private Replay replay(double speed, String drop) {
        RecordedScoreFeed feed = new RecordedScoreFeed(lines, "tst001", System.currentTimeMillis(), System::currentTimeMillis, speed);
        Poller poller = new Poller(feed, pool, sink(drop), System::currentTimeMillis);
        replay = new Replay(feed, poller, pool, Replay.pollMillis(speed));
        return replay;
    }

    @Test
    void primeCreatesEveryGameRecord() {
        Replay r = replay(600, null);
        r.prime(Duration.ofSeconds(10));
        for (String id : List.of("nfl-rptst001-401872971", "nhl-rptst001-401891803")) {
            assertTrue(jedis.exists(RedisKeys.GAME_PREFIX + id), id);
            assertEquals("SCHEDULED", jedis.hget(RedisKeys.GAME_PREFIX + id, "status"));
        }
    }

    @Test
    void primeTimesOutNamingMissingGames() {
        Replay r = replay(600, "nhl-rptst001-401891803");
        IllegalStateException e = assertThrows(IllegalStateException.class, () -> r.prime(Duration.ofSeconds(2)));
        assertTrue(e.getMessage().contains("nhl-rptst001-401891803"), e.getMessage());
        assertFalse(e.getMessage().contains("nfl-rptst001"), e.getMessage());
    }

    @Test
    void runReachesFinalForEveryGame() throws Exception {
        Replay r = replay(600, null);
        r.prime(Duration.ofSeconds(10));
        r.start();
        long until = System.currentTimeMillis() + 30_000;
        while (!(r.feedDone() && r.notFinal().isEmpty()) && System.currentTimeMillis() < until) Thread.sleep(200);
        assertTrue(r.feedDone());
        assertEquals(List.of(), r.notFinal());
    }

    @Test
    void pollMillisFloorIs500() {
        assertEquals(500, Replay.pollMillis(600));
        assertEquals(3_000, Replay.pollMillis(10));
        assertEquals(30_000, Replay.pollMillis(1));
    }
}

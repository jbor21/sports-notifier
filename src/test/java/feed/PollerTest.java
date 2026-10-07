package feed;

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
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.resps.StreamEntry;
import service.ProcessResult;
import service.impl.EventProcessingServiceImpl;
import util.GameEventParser;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static model.GameSnapshot.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class PollerTest {

    private static final long NOW = 1_790_000_000_000L;
    private static final long MINUTE = 60_000L;

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl eps;
    private final FakeFeed feed = new FakeFeed();

    /** Returns whatever the test last set. */
    static final class FakeFeed implements ScoreFeed {
        volatile List<GameSnapshot> games = List.of();
        @Override public List<GameSnapshot> fetch(String league) { return games; }
    }

    /** Stands in for POST /events: the real service, mapped to HTTP statuses. */
    final class InProcessSink implements EventSink {
        final List<String> postedIds = new CopyOnWriteArrayList<>();
        @Override public PostResult post(GameEvent event) {
            postedIds.add(event.getEventId());
            ProcessResult r = eps.processEvent(event);
            return new PostResult(r == ProcessResult.INVALID ? 400 : 200, r.name().toLowerCase());
        }
    }

    /** Fails the first POST with 503, like an Event Processing restart, then behaves. */
    final class FailOnceSink implements EventSink {
        final InProcessSink delegate = new InProcessSink();
        final AtomicBoolean failed = new AtomicBoolean();
        @Override public PostResult post(GameEvent event) {
            if (failed.compareAndSet(false, true)) {
                delegate.postedIds.add(event.getEventId());
                return new PostResult(503, null);
            }
            return delegate.post(event);
        }
    }

    @BeforeEach
    void setUp() {
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        eps = new EventProcessingServiceImpl(pool);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private static GameSnapshot game(String id, Phase phase, String status, int clockTenths,
                                     int home, int away, long start) {
        Stage stage = "POSTPONED".equals(status) ? Stage.DELAYED : Stage.IN_PLAY;
        return new GameSnapshot(id, "NBA", "LAL", "BOS", home, away, phase,
            phase == PRE ? 0 : 1, Half.TOP, clockTenths, 0, stage, status, start);
    }

    private static GameSnapshot scheduled(long start) {
        return game("espn-1", PRE, "SCHEDULED", 0, 0, 0, start);
    }

    private static GameSnapshot live(int clockTenths, int home, int away) {
        return game("espn-1", LIVE, "IN_PROGRESS", clockTenths, home, away, NOW - MINUTE);
    }

    private Poller poller(EventSink sink) {
        return new Poller(feed, pool, sink, () -> NOW);
    }

    private List<String> streamTypes() {
        List<String> types = new ArrayList<>();
        for (StreamEntry e : jedis.xrange("game-events", (StreamEntryID) null, (StreamEntryID) null)) {
            types.add(GameEventParser.parse(e.getFields().get("data")).getEventType());
        }
        return types;
    }

    @Test
    void firstSightIsQuiet() throws Exception {
        feed.games = List.of(live(3000, 50, 48), game("espn-2", FINAL, "FINAL", 0, 101, 99, NOW - 3 * 3600_000L));
        poller(new InProcessSink()).pollLeague("NBA");
        assertTrue(jedis.exists("game:espn-1"));
        assertTrue(jedis.exists("game:espn-2"));
        assertEquals(0L, jedis.xlen("game-events"));
    }

    @Test
    void scheduledThenLiveSendsOneStart() throws Exception {
        Poller p = poller(new InProcessSink());
        feed.games = List.of(scheduled(NOW + MINUTE));
        p.pollLeague("NBA");
        feed.games = List.of(live(7200, 0, 0));
        p.pollLeague("NBA");
        p.pollLeague("NBA");   // nothing new the second time
        assertEquals(List.of("GAME_START"), streamTypes());
    }

    @Test
    void twoPollersSendEachChangeOnce() throws Exception {
        // A rolling update runs the old and new poller side by side for a few seconds.
        Poller a = poller(new InProcessSink());
        Poller b = poller(new InProcessSink());
        feed.games = List.of(scheduled(NOW + MINUTE));
        a.pollLeague("NBA");

        feed.games = List.of(live(7200, 2, 0));
        pollTogether(a, b);
        feed.games = List.of(live(7000, 2, 3));
        pollTogether(a, b);

        assertEquals(List.of("GAME_START", "SCORE_UPDATE"), streamTypes());
    }

    private static void pollTogether(Poller a, Poller b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch gate = new CountDownLatch(1);
            Future<?> fa = pool.submit(() -> { gate.await(); return a.pollLeague("NBA"); });
            Future<?> fb = pool.submit(() -> { gate.await(); return b.pollLeague("NBA"); });
            gate.countDown();
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedPostIsRetriedByNextPoll() throws Exception {
        Poller setup = poller(new InProcessSink());
        feed.games = List.of(scheduled(NOW + MINUTE));
        setup.pollLeague("NBA");

        FailOnceSink sink = new FailOnceSink();
        Poller p = poller(sink);
        feed.games = List.of(live(7200, 0, 0));
        p.pollLeague("NBA");
        assertEquals(0L, jedis.xlen("game-events"));
        assertEquals("SCHEDULED", jedis.hget("game:espn-1", "status"));

        p.pollLeague("NBA");
        assertEquals(List.of("GAME_START"), streamTypes());
        assertEquals(2, sink.delegate.postedIds.size());
        assertEquals(sink.delegate.postedIds.get(0), sink.delegate.postedIds.get(1));
    }

    @Test
    void expiredFinalSeenAgainIsQuiet() throws Exception {
        Poller p = poller(new InProcessSink());
        feed.games = List.of(live(100, 99, 99));
        p.pollLeague("NBA");
        feed.games = List.of(game("espn-1", FINAL, "FINAL", 0, 101, 99, NOW - MINUTE));
        p.pollLeague("NBA");
        assertEquals(List.of("GAME_END"), streamTypes());

        // In production the record lives 24 h after the final and the dedup keys 1 h, so by the
        // time the record expires the dedup keys are long gone. Model both expiring.
        jedis.del("game:espn-1");
        jedis.keys("processed:*").forEach(jedis::del);
        p.pollLeague("NBA");
        assertEquals(List.of("GAME_END"), streamTypes());
        assertEquals("FINAL", jedis.hget("game:espn-1", "status"));
    }

    @Test
    void liveLeagueIsFast() throws Exception {
        feed.games = List.of(live(3000, 1, 1));
        assertTrue(poller(new InProcessSink()).pollLeague("NBA"));
    }

    @Test
    void gameStartingSoonIsFast() throws Exception {
        feed.games = List.of(scheduled(NOW + 5 * MINUTE));
        assertTrue(poller(new InProcessSink()).pollLeague("NBA"));
    }

    @Test
    void lateStartIsFast() throws Exception {
        feed.games = List.of(scheduled(NOW - 3 * MINUTE));
        assertTrue(poller(new InProcessSink()).pollLeague("NBA"));
    }

    @Test
    void farFutureGameIsSlow() throws Exception {
        feed.games = List.of(scheduled(NOW + 120 * MINUTE));
        assertFalse(poller(new InProcessSink()).pollLeague("NBA"));
    }

    @Test
    void postponedIsSlow() throws Exception {
        feed.games = List.of(game("espn-1", PRE, "POSTPONED", 0, 0, 0, NOW - 3 * MINUTE));
        assertFalse(poller(new InProcessSink()).pollLeague("NBA"));
    }

    @Test
    void suspendedGameIsSlow() throws Exception {
        // A suspended MLB game can sit on the scoreboard all day; it must not keep MLB on 5 s polling.
        feed.games = List.of(new GameSnapshot("espn-1", "MLB", "CHC", "STL", 2, 2, LIVE, 5, Half.TOP, 0, 1,
            Stage.DELAYED, "SUSPENDED", NOW - 3 * 3600_000L));
        assertFalse(poller(new InProcessSink()).pollLeague("MLB"));
    }

    @Test
    void emptyScoreboardIsSlow() throws Exception {
        feed.games = List.of();
        assertFalse(poller(new InProcessSink()).pollLeague("NBA"));
    }
}

package service.impl;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import service.ProcessResult;
import testsupport.TestPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The race from the course project: 50 events for one game arriving at once. The score came out
 * 43, then 48 after a partial fix, then 50 once check, claim, and write were one Lua script.
 * This was measured by hand in the course; here it runs on every push.
 *
 * Since Phase 3.1 a game has at most one unsent play, so most of a burst is answered "pending".
 * Each thread retries until it gets a final answer, exactly as the poller does on its next poll.
 */
class EventProcessingBurstTest {

    private static final int EVENTS = 50;
    private static final int RUNS = 5;

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl service;
    private TestPublisher sent;

    @BeforeEach
    void setUp() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(EVENTS + 4);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        sent = new TestPublisher(true);
        service = new EventProcessingServiceImpl(pool, sent, System::currentTimeMillis);
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    @Test
    void fiftyConcurrentEventsEndAtFifty() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(EVENTS);
        try {
            for (int run = 0; run < RUNS; run++) {
                String gameId = "burst-" + run;
                CountDownLatch startGate = new CountDownLatch(1);
                List<Future<?>> done = new ArrayList<>();
                AtomicInteger accepted = new AtomicInteger();
                for (int i = 1; i <= EVENTS; i++) {
                    final int seq = i;
                    done.add(threads.submit(() -> {
                        startGate.await();
                        GameEvent e = new GameEvent(gameId, gameId + "-e" + seq, "SCORE_UPDATE", "LAL",
                            1000L + seq, "score", "LAL", "BOS", seq, 0, "IN_PROGRESS", "NBA", seq);
                        ProcessResult r;
                        while ((r = service.processEvent(e)) == ProcessResult.PENDING) Thread.sleep(1);
                        if (r == ProcessResult.ACCEPTED) accepted.incrementAndGet();
                        return null;
                    }));
                }
                startGate.countDown();
                for (Future<?> f : done) f.get(10, TimeUnit.SECONDS);

                assertEquals("50", jedis.hget("game:" + gameId, "homeScore"), "run " + run);
                assertEquals("50", jedis.hget("game:" + gameId, "lastSeq"), "run " + run);
                // Published in version order, one step per accept, and seq rises with it: no heal fired,
                // nothing reordered. The counter starts from the clock, so steps are checked, not raw numbers.
                List<GameEvent> published = new ArrayList<>();
                synchronized (sent) {
                    for (GameEvent p : sent.plays) if (gameId.equals(p.getGameId())) published.add(p);
                }
                assertEquals(accepted.get(), published.size(), "run " + run);
                long first = published.get(0).getVersion();
                // version counts accepted plays only; the stale ones are not counted.
                assertEquals(String.valueOf(first + accepted.get() - 1), jedis.hget("game:" + gameId, "version"), "run " + run);
                for (int k = 0; k < published.size(); k++) {
                    assertEquals(first + k, published.get(k).getVersion(), "run " + run);
                    if (k > 0) assertTrue(published.get(k).getSeq() > published.get(k - 1).getSeq(), "run " + run);
                }
            }
        } finally {
            threads.shutdownNow();
        }
    }
}

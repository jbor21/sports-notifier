package service.impl;

import model.GameEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The race from the course project: 50 events for one game arriving at once. The score came out
 * 43, then 48 after a partial fix, then 50 once check, claim, and write were one Lua script.
 * This was measured by hand in the course; here it runs on every push.
 */
class EventProcessingBurstTest {

    private static final int EVENTS = 50;
    private static final int RUNS = 5;

    private JedisPool pool;
    private Jedis jedis;
    private EventProcessingServiceImpl service;

    @BeforeEach
    void setUp() {
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(EVENTS + 4);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        service = new EventProcessingServiceImpl(pool);
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
                for (int i = 1; i <= EVENTS; i++) {
                    final int seq = i;
                    done.add(threads.submit(() -> {
                        startGate.await();
                        service.processEvent(new GameEvent(gameId, gameId + "-e" + seq, "SCORE_UPDATE", "LAL",
                            1000L + seq, "score", "LAL", "BOS", seq, 0, "IN_PROGRESS", "NBA", seq));
                        return null;
                    }));
                }
                startGate.countDown();
                for (Future<?> f : done) f.get(10, TimeUnit.SECONDS);

                assertEquals("50", jedis.hget("game:" + gameId, "homeScore"), "run " + run);
                assertEquals("50", jedis.hget("game:" + gameId, "lastSeq"), "run " + run);
            }
        } finally {
            threads.shutdownNow();
        }
    }
}

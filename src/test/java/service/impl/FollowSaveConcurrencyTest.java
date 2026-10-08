package service.impl;

import catalog.AlertCatalog;
import catalog.Teams;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import testsupport.PostgresTestSupport;
import util.ApiResult;
import util.RedisKeys;

import java.sql.Connection;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Commit first, then Redis; the marker; the per-user and rebuild locks (spec 5.3). */
class FollowSaveConcurrencyTest {

    private JedisPool pool;
    private Jedis jedis;
    private String bob;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        pool = new JedisPool(new JedisPoolConfig(), "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        bob = new AccountService(PostgresTestSupport.dataSource()).signup("bob", "password-123", null).body().id();
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    /** Redis writes that fail a set number of times. */
    static final class FailingSets extends FollowSets {
        final AtomicInteger writeFailures;
        final AtomicInteger markerFailures;

        FailingSets(JedisPool pool, int writeFailures, int markerFailures) {
            super(pool);
            this.writeFailures = new AtomicInteger(writeFailures);
            this.markerFailures = new AtomicInteger(markerFailures);
        }

        @Override
        public void writeState(Follow f) {
            if (writeFailures.getAndDecrement() > 0) throw new IllegalStateException("Redis is down (test)");
            super.writeState(f);
        }

        @Override
        public void deleteMarker() {
            if (markerFailures.getAndDecrement() > 0) throw new IllegalStateException("Redis is down (test)");
            super.deleteMarker();
        }
    }

    private FollowService service(FollowSets sets) {
        return new FollowService(PostgresTestSupport.dataSource(), sets, AlertCatalog.load(), Teams.load(), id -> Optional.empty());
    }

    @Test
    void redisFailureAfterCommitDeletesMarker() {
        jedis.set(RedisKeys.SUB_BUILT, "1");
        FollowService follows = service(new FailingSets(pool, 1, 0));
        ApiResult<Follow> r = follows.create(bob, "team", "mlb-chw");
        assertEquals(201, r.status(), "the follow is saved; Redis catches up at the rebuild");
        assertEquals(1, follows.list(bob).body().size());
        assertFalse(jedis.exists(RedisKeys.SUB_BUILT));
        assertFalse(follows.rebuildOwed());
    }

    @Test
    void redisUnreachableSetsRebuildOwed() {
        jedis.set(RedisKeys.SUB_BUILT, "1");
        FollowService follows = service(new FailingSets(pool, 1, 1));
        assertEquals(201, follows.create(bob, "team", "mlb-chw").status());
        assertTrue(follows.rebuildOwed());
        follows.clearRebuildOwed();
        assertFalse(follows.rebuildOwed());
    }

    @Test
    void twoConcurrentPatchesBothLand() throws Exception {
        FollowService follows = service(new FollowSets(pool));
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 200; i++) {
                boolean on = i % 2 == 0;
                CyclicBarrier gate = new CyclicBarrier(2);
                Future<Integer> a = threads.submit(() -> { gate.await();
                    return follows.patch(bob, id, FollowServiceTest.body("{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":" + on + "}}}")).status(); });
                Future<Integer> b = threads.submit(() -> { gate.await();
                    return follows.patch(bob, id, FollowServiceTest.body("{\"alerts\":{\"BIG_INNING\":{\"enabled\":" + on + "}}}")).status(); });
                assertEquals(200, a.get(10, TimeUnit.SECONDS));
                assertEquals(200, b.get(10, TimeUnit.SECONDS));
                Follow row = follows.get(bob, id).body();
                assertEquals(on, row.alerts().get("LEAD_CHANGE").enabled(), "iteration " + i);
                assertEquals(on, row.alerts().get("BIG_INNING").enabled(), "iteration " + i);
                row.alerts().forEach((type, s) -> assertEquals(s.enabled(),
                        jedis.sismember(FollowSets.key(row.kind(), row.targetId(), type), bob), type + " in Redis matches Postgres"));
            }
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void saveWaitsForRebuildLock() throws Exception {
        FollowService follows = service(new FollowSets(pool));
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> rebuild = threads.submit(() -> {
                try (Connection c = PostgresTestSupport.dataSource().getConnection()) {
                    return SubLocks.withRebuildLock(c, conn -> { held.countDown(); release.await(); return true; });
                }
            });
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<Integer> save = threads.submit(() -> follows.create(bob, "team", "mlb-chw").status());
            assertThrows(TimeoutException.class, () -> save.get(500, TimeUnit.MILLISECONDS), "the save waits for the rebuild");
            release.countDown();
            assertTrue(rebuild.get(10, TimeUnit.SECONDS));
            assertEquals(201, save.get(10, TimeUnit.SECONDS));
        } finally {
            threads.shutdownNow();
        }
    }
}

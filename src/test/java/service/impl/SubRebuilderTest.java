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
import util.RedisKeys;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Rebuilding the sub:* sets from Postgres (spec 5.4). */
class SubRebuilderTest {

    private JedisPool pool;
    private Jedis jedis;
    private FollowService follows;
    private String bob;
    private String alice;

    @BeforeEach
    void setUp() {
        PostgresTestSupport.truncateAll();
        JedisPoolConfig cfg = new JedisPoolConfig();
        cfg.setMaxTotal(16);
        pool = new JedisPool(cfg, "localhost", 6379, 2000, null, 1);
        jedis = new Jedis("localhost", 6379);
        jedis.select(1);
        jedis.flushDB();
        AccountService accounts = new AccountService(PostgresTestSupport.dataSource());
        bob = accounts.signup("bob", "password-123", null).body().id();
        alice = accounts.signup("alice", "password-123", null).body().id();
        follows = new FollowService(PostgresTestSupport.dataSource(), pool, AlertCatalog.load(), Teams.load(), id -> Optional.empty());
    }

    @AfterEach
    void tearDown() {
        jedis.close();
        pool.close();
    }

    private SubRebuilder rebuilder() {
        return new SubRebuilder(PostgresTestSupport.dataSource(), pool, follows::rebuildOwed, follows::clearRebuildOwed);
    }

    @Test
    void rebuildMatchesPostgres() {
        follows.create(bob, "team", "mlb-chw");
        String id = follows.create(alice, "team", "mlb-chw").body().id();
        follows.patch(alice, id, FollowServiceTest.body("{\"alerts\":{\"LEAD_CHANGE\":{\"enabled\":true}}}"));
        follows.create(alice, "league", "NFL");
        Set<String> before = snapshot();
        jedis.flushDB();                                   // Redis came back empty

        assertTrue(rebuilder().checkOnce());
        assertEquals(before, snapshot());
        assertTrue(jedis.exists(RedisKeys.SUB_BUILT));
    }

    @Test
    void markerPresentNoRebuild() {
        jedis.set(RedisKeys.SUB_BUILT, "x");
        follows.create(bob, "team", "mlb-chw");
        jedis.del(FollowSets.key(catalog.FollowKind.TEAM, "mlb-chw", "GAME_START"));
        assertFalse(rebuilder().checkOnce());
        assertFalse(jedis.exists(FollowSets.key(catalog.FollowKind.TEAM, "mlb-chw", "GAME_START")));
    }

    @Test
    void rebuildDeletesStaleSetsAndLeftoverTemps() {
        jedis.sadd("sub:team:nba-lal:GAME_START", "someone-who-unfollowed");
        jedis.sadd(RedisKeys.SUB_TMP_PREFIX + "deadbuild:sub:team:x:FINAL", "u");
        jedis.sadd("sub:team:LAL", "a-3.1-member");          // 3.1 shapes: nothing reads them any more
        jedis.sadd(RedisKeys.SUB_NOTIF_PREFIX + "GAME_START", "a-3.1-member");
        follows.create(bob, "team", "mlb-chw");
        jedis.del(RedisKeys.SUB_BUILT);

        assertTrue(rebuilder().checkOnce());
        assertFalse(jedis.exists("sub:team:nba-lal:GAME_START"));
        assertTrue(jedis.keys(RedisKeys.SUB_TMP_PREFIX + "*").isEmpty());
        assertFalse(jedis.exists("sub:team:LAL"));
        assertFalse(jedis.exists(RedisKeys.SUB_NOTIF_PREFIX + "GAME_START"));
        assertTrue(jedis.sismember("sub:team:mlb-chw:GAME_START", bob));
    }

    @Test
    void expiredFollowsSkipped() throws Exception {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        try (Connection c = PostgresTestSupport.dataSource().getConnection(); Statement st = c.createStatement()) {
            st.execute("UPDATE follows SET expires_at = now() - interval '1 minute' WHERE id = '" + id + "'");
        }
        jedis.flushDB();
        rebuilder().checkOnce();
        assertFalse(jedis.sismember("sub:team:mlb-chw:GAME_START", bob));
    }

    @Test
    void owedMarkerDeleteRetried() {
        jedis.set(RedisKeys.SUB_BUILT, "x");
        AtomicBoolean owed = new AtomicBoolean(true);
        SubRebuilder r = new SubRebuilder(PostgresTestSupport.dataSource(), pool, owed::get, () -> owed.set(false));
        assertTrue(r.checkOnce(), "the owed delete happens, then the missing marker triggers a rebuild");
        assertFalse(owed.get());
        assertTrue(jedis.exists(RedisKeys.SUB_BUILT));
    }

    @Test
    void twoRebuildersOneRuns() throws Exception {
        follows.create(bob, "team", "mlb-chw");
        jedis.del(RedisKeys.SUB_BUILT);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier gate = new CyclicBarrier(2);
            Future<Boolean> a = threads.submit(() -> { gate.await(); return rebuilder().checkOnce(); });
            Future<Boolean> b = threads.submit(() -> { gate.await(); return rebuilder().checkOnce(); });
            int rebuilt = (a.get(30, TimeUnit.SECONDS) ? 1 : 0) + (b.get(30, TimeUnit.SECONDS) ? 1 : 0);
            assertEquals(1, rebuilt);
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void unfollowDuringRebuildStaysUnfollowed() throws Exception {
        String id = follows.create(bob, "team", "mlb-chw").body().id();
        jedis.del(RedisKeys.SUB_BUILT);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        SubRebuilder paused = new SubRebuilder(PostgresTestSupport.dataSource(), pool, () -> false, () -> {}, () -> {
            read.countDown();
            try { resume.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> rebuild = threads.submit(paused::checkOnce);
            assertTrue(read.await(10, TimeUnit.SECONDS), "the rebuild has read Bob's follow");
            Future<Integer> unfollow = threads.submit(() -> follows.delete(bob, id).status());
            assertThrows(TimeoutException.class, () -> unfollow.get(500, TimeUnit.MILLISECONDS), "the unfollow waits");
            resume.countDown();
            assertTrue(rebuild.get(10, TimeUnit.SECONDS));
            assertEquals(204, unfollow.get(10, TimeUnit.SECONDS));
            assertFalse(jedis.sismember("sub:team:mlb-chw:GAME_START", bob), "the rebuild did not put Bob back");
        } finally {
            threads.shutdownNow();
        }
    }

    @Test
    void readersNeverSeeAnEmptySetForAFollowedTarget() throws Exception {
        for (int i = 0; i < 50; i++) {
            String user = new AccountService(PostgresTestSupport.dataSource()).signup("fan" + i, "password-123", null).body().id();
            follows.create(user, "team", "mlb-chw");
        }
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger emptyReads = new AtomicInteger();
        AtomicInteger reads = new AtomicInteger();
        Thread reader = new Thread(() -> {
            try (Jedis r = new Jedis("localhost", 6379)) {
                r.select(1);
                while (!stop.get()) {
                    if (r.scard("sub:team:mlb-chw:GAME_START") == 0) emptyReads.incrementAndGet();
                    reads.incrementAndGet();
                }
            }
        });
        reader.start();
        try {
            for (int i = 0; i < 20; i++) rebuilder().rebuildNow(true);
        } finally {
            stop.set(true);
            reader.join(5000);
        }
        assertTrue(reads.get() > 0);
        assertEquals(0, emptyReads.get(), "a reader saw the set empty during a rebuild");
    }

    /** Every (set, member) pair under sub:, except the marker. */
    private Set<String> snapshot() {
        Set<String> out = new TreeSet<>();
        for (String key : jedis.keys("sub:*")) {
            if (key.equals(RedisKeys.SUB_BUILT)) continue;
            for (String m : jedis.smembers(key)) out.add(key + " " + m);
        }
        return out;
    }
}

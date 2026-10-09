package simulator;

import feed.Poller;
import feed.RecordedScoreFeed;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs one replay set through the real {@link Poller} (Phase 4.1 spec, 2.3): {@link #prime} gets every
 * game its record before the run, {@link #start} starts replay time and polls every league on a
 * thread until {@link #stop}. Used by {@link ReplayMain} and by the checker, which runs it in its own
 * process so it knows exactly when the feed is done.
 *
 * A play answered {@code pending} needs nothing here: the poller sees the same difference on its next
 * poll and sends it again (issue #7).
 */
public final class Replay {

    private static final String[] LEAGUES = {"NFL", "NBA", "MLB", "NHL"};

    private final RecordedScoreFeed feed;
    private final Poller poller;
    private final JedisPool pool;
    private final long pollMillis;
    private volatile boolean running;
    private Thread loop;

    public Replay(RecordedScoreFeed feed, Poller poller, JedisPool pool, long pollMillis) {
        this.feed = feed;
        this.poller = poller;
        this.pool = pool;
        this.pollMillis = pollMillis;
    }

    /** The recorder's 30 s cadence at {@code speed}, never below 500 ms. */
    public static long pollMillis(double speed) {
        return Math.max(500L, Math.round(30_000 / speed));
    }

    /** Polls the pre-game snapshots until every game has a {@code game:} record. */
    public void prime(Duration timeout) {
        long until = System.currentTimeMillis() + timeout.toMillis();
        List<String> missing = feed.gameIds();
        while (true) {
            pollAll();
            missing = missing(feed.gameIds());
            if (missing.isEmpty()) return;
            if (System.currentTimeMillis() >= until) {
                throw new IllegalStateException("replay games without a record after priming: " + missing);
            }
            sleep(Math.min(pollMillis, 500L));
        }
    }

    /** Starts replay time and the polling thread. */
    public void start() {
        feed.startClock();
        running = true;
        loop = new Thread(() -> {
            while (running) {
                pollAll();
                sleep(pollMillis);
            }
        }, "replay");
        loop.setDaemon(true);
        loop.start();
    }

    public boolean feedDone() {
        return feed.done();
    }

    public List<String> gameIds() {
        return feed.gameIds();
    }

    /** The run's games whose record is not {@code FINAL} yet. */
    public List<String> notFinal() {
        return notFinal(pool, feed.gameIds());
    }

    public void stop() {
        running = false;
        if (loop != null) loop.interrupt();
    }

    /** {@code gameIds} whose {@code game:} status is not {@code FINAL}; all of them if Redis can't be read. */
    static List<String> notFinal(JedisPool pool, List<String> gameIds) {
        List<String> open = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (String id : gameIds) {
                if (!"FINAL".equals(jedis.hget(RedisKeys.GAME_PREFIX + id, "status"))) open.add(id);
            }
        } catch (Exception e) {
            System.err.println("[Replay] reading game records failed: " + e);
            return gameIds;
        }
        return open;
    }

    private List<String> missing(List<String> gameIds) {
        List<String> out = new ArrayList<>();
        try (Jedis jedis = pool.getResource()) {
            for (String id : gameIds) {
                if (!jedis.exists(RedisKeys.GAME_PREFIX + id)) out.add(id);
            }
        }
        return out;
    }

    private void pollAll() {
        for (String league : LEAGUES) {
            try {
                poller.pollLeague(league);
            } catch (Exception e) {
                System.err.println("[Replay] " + league + " poll failed: " + e);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

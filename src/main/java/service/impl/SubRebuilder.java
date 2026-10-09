package service.impl;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import util.RedisKeys;
import util.RedisPipelines;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Rebuilds the sub:* sets from Postgres whenever the {@code sub:built} marker does not vouch for
 * them, and once a day regardless (spec 5.4). The marker is missing when Redis restarts empty or a
 * save's Redis update fails; it is wrong when it was written for another set shape (a helm upgrade,
 * #16) or by another Redis run (an AOF restart that lost recent writes, #18). All of these end here.
 */
public final class SubRebuilder {

    private static final int BATCH = 1000;

    private final DataSource db;
    private final JedisPool pool;
    private final BooleanSupplier rebuildOwed;
    private final Runnable clearOwed;
    /** Runs once, after the first target's follows are read and before its set is written. Tests pause here. */
    private final Runnable afterFirstRead;

    /** Reads Redis's run_id, which changes whenever Redis restarts. Tests substitute their own. */
    private final Function<Jedis, String> runId;

    public SubRebuilder(DataSource db, JedisPool pool, BooleanSupplier rebuildOwed, Runnable clearOwed) {
        this(db, pool, rebuildOwed, clearOwed, () -> {});
    }

    SubRebuilder(DataSource db, JedisPool pool, BooleanSupplier rebuildOwed, Runnable clearOwed, Runnable afterFirstRead) {
        this(db, pool, rebuildOwed, clearOwed, afterFirstRead, SubRebuilder::redisRunId);
    }

    SubRebuilder(DataSource db, JedisPool pool, BooleanSupplier rebuildOwed, Runnable clearOwed, Runnable afterFirstRead,
                 Function<Jedis, String> runId) {
        this.db = db;
        this.pool = pool;
        this.rebuildOwed = rebuildOwed;
        this.clearOwed = clearOwed;
        this.afterFirstRead = afterFirstRead;
        this.runId = runId;
    }

    /** The {@code run_id:} line of {@code INFO server}. */
    static String redisRunId(Jedis jedis) {
        for (String line : jedis.info("server").split("\r?\n")) {
            if (line.startsWith("run_id:")) return line.substring("run_id:".length()).trim();
        }
        return null;
    }

    /** Retries a marker delete a save couldn't do, then rebuilds unless the marker vouches for the sets. True if it rebuilt. */
    public boolean checkOnce() {
        try (Jedis jedis = pool.getResource()) {
            if (rebuildOwed.getAsBoolean()) {
                jedis.del(RedisKeys.SUB_BUILT);
                clearOwed.run();
            }
            if (vouched(jedis)) return false;
        }
        return rebuildNow(false);
    }

    /**
     * The marker is today's shape and was written by the Redis now running. A Redis that restarted
     * from its AOF can keep sub:built but lose the last second of SADD/SREM (fsync every second); its
     * run_id changes on every start. The run_id is kept in the marker, not in this process, so a
     * user-pref that restarted together with Redis still sees the difference.
     */
    private boolean vouched(Jedis jedis) {
        if (!FollowSets.isBuilt(jedis)) return false;
        String live = runId.apply(jedis);
        String built = jedis.hget(RedisKeys.SUB_BUILT, "runId");
        if (live != null && !live.equals(built)) {
            System.out.println("[SubRebuilder] Redis restarted since the last build (run_id changed); rebuilding");
            return false;
        }
        return true;
    }

    /** {@code force}: rebuild even if the marker is there (the daily rebuild). True if it rebuilt. */
    public boolean rebuildNow(boolean force) {
        try (Connection c = db.getConnection()) {
            return SubLocks.withRebuildLock(c, conn -> {
                try (Jedis jedis = pool.getResource()) {
                    // Checked again under the lock: another user-pref replica may have just rebuilt.
                    if (!force && vouched(jedis)) return false;
                    long started = System.currentTimeMillis();
                    String buildId = UUID.randomUUID().toString();
                    Set<String> produced = buildAll(conn, jedis, buildId);
                    int removed = cleanup(jedis, produced);
                    FollowSets.markBuilt(jedis, runId.apply(jedis));
                    System.out.println("[SubRebuilder] rebuilt " + produced.size() + " sets, removed " + removed
                            + ", in " + (System.currentTimeMillis() - started) + " ms");
                    return true;
                }
            });
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("rebuilding the sub sets failed", e);
        }
    }

    public void start(ScheduledExecutorService scheduler, long checkMs, long fullRebuildHours) {
        scheduler.scheduleWithFixedDelay(() -> run("check", this::checkOnce), 0, checkMs, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(() -> run("daily rebuild", () -> rebuildNow(true)),
                fullRebuildHours, fullRebuildHours, TimeUnit.HOURS);
    }

    private static void run(String what, BooleanSupplier job) {
        try {
            job.getAsBoolean();
        } catch (RuntimeException e) {
            System.err.println("[SubRebuilder] " + what + " failed, next run retries: " + e.getMessage());
        }
    }

    /**
     * Streams unexpired follows one target at a time (ordered by target, so memory holds one target's
     * members), writes each set under a temporary name, and renames it over the live one. RENAME is
     * atomic, so Stage 1 never reads a half-built set.
     */
    private Set<String> buildAll(Connection conn, Jedis jedis, String buildId) throws Exception {
        Set<String> produced = new HashSet<>();
        boolean firstWritten = false;
        conn.setAutoCommit(false);   // the driver streams with a fetch size only inside a transaction
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, user_id, kind, target_id, league, alerts, expires_at, created_at FROM follows"
                        + " WHERE expires_at IS NULL OR expires_at > now() ORDER BY kind, target_id")) {
            ps.setFetchSize(BATCH);
            try (ResultSet rs = ps.executeQuery()) {
                String target = null;
                Map<String, List<String>> members = new LinkedHashMap<>();
                while (rs.next()) {
                    Follow f = FollowStore.read(rs);
                    String thisTarget = FollowSets.prefix(f.kind()) + f.targetId();
                    if (target != null && !target.equals(thisTarget)) {
                        if (!firstWritten) { afterFirstRead.run(); firstWritten = true; }
                        write(jedis, buildId, target, members, produced);
                        members.clear();
                    }
                    target = thisTarget;
                    for (String name : FollowSets.memberships(f)) {
                        members.computeIfAbsent(name, t -> new ArrayList<>()).add(f.userId());
                    }
                }
                if (target != null) {
                    if (!firstWritten) afterFirstRead.run();
                    write(jedis, buildId, target, members, produced);
                }
            }
        } finally {
            conn.commit();
            conn.setAutoCommit(true);
        }
        return produced;
    }

    private static void write(Jedis jedis, String buildId, String targetPrefix, Map<String, List<String>> members,
                              Set<String> produced) {
        for (Map.Entry<String, List<String>> e : members.entrySet()) {
            String live = targetPrefix + ":" + e.getKey();
            String tmp = RedisKeys.SUB_TMP_PREFIX + buildId + ":" + live;
            Pipeline p = jedis.pipelined();
            List<String> users = e.getValue();
            for (int i = 0; i < users.size(); i += BATCH) {
                p.sadd(tmp, users.subList(i, Math.min(users.size(), i + BATCH)).toArray(new String[0]));
            }
            p.rename(tmp, live);
            RedisPipelines.syncChecked(p);
            produced.add(live);
        }
    }

    /**
     * Deletes sets this build did not produce (targets nobody follows any more, switches nobody has
     * on), temporary sets a crashed build left behind, and the 3.1 sets ({@code sub:team:<team>},
     * {@code sub:notif:<type>}), which nothing reads any more.
     */
    private static int cleanup(Jedis jedis, Set<String> produced) {
        int removed = 0;
        for (String pattern : List.of(RedisKeys.SUB_LEAGUE_PREFIX + "*", RedisKeys.SUB_TEAM_PREFIX + "*",
                RedisKeys.SUB_GAME_PREFIX + "*", RedisKeys.SUB_TMP_PREFIX + "*", RedisKeys.SUB_NOTIF_PREFIX + "*")) {
            boolean always = pattern.startsWith(RedisKeys.SUB_TMP_PREFIX) || pattern.startsWith(RedisKeys.SUB_NOTIF_PREFIX);
            ScanParams params = new ScanParams().match(pattern).count(BATCH);
            String cursor = ScanParams.SCAN_POINTER_START;
            do {
                ScanResult<String> page = jedis.scan(cursor, params);
                for (String key : page.getResult()) {
                    if (always || !produced.contains(key)) {
                        jedis.del(key);
                        removed++;
                    }
                }
                cursor = page.getCursor();
            } while (!cursor.equals(ScanParams.SCAN_POINTER_START));
        }
        return removed;
    }
}

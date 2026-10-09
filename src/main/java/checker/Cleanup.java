package checker;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Removes a run's synthetic users (Phase 4.1 spec, 7). Postgres keeps its data across {@code make down},
 * and only {@code follows} cascades from {@code users}: history and read markers have no foreign key,
 * and the {@code sub:*} sets in Redis would keep the users, so Stage 1 would keep sending to them.
 */
public final class Cleanup {

    /** A run's usernames, {@code ck_<6 base-36>_<n>}; a person can't easily pick one by accident. */
    private static final String CHECKER_NAME = "^ck_[0-9a-z]{6}_[0-9]+$";

    private Cleanup() {}

    /**
     * Unfollows through the API (which takes the users out of the sub:* sets), signs out, then deletes
     * the rest. If an unfollow failed, or users were deleted whose sessions the checker never got back
     * (a setup that failed part way), the sets may still hold them, and Stage 1 would keep sending to
     * users that no longer exist: the marker is deleted so user-pref rebuilds the sets from Postgres.
     */
    public static int run(Api api, List<Session> sessions, Map<String, List<String>> followIdsByUser,
                          DataSource db, JedisPool pool, String runId) {
        boolean unfollowFailed = false;
        for (Session s : sessions) {
            for (String id : followIdsByUser.getOrDefault(s.userId(), List.of())) {
                try {
                    api.deleteFollow(s, id);
                } catch (SetupException e) {
                    unfollowFailed = true;
                }
            }
            try {
                api.logout(s);
            } catch (SetupException ignored) {
                // the session expires on its own
            }
        }
        int n = deleteRun(db, pool, runId);
        if (unfollowFailed || n > sessions.size()) {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(RedisKeys.SUB_BUILT);
            }
        }
        return n;
    }

    /** Deletes the run's users with their history, read markers, and unread counters. */
    public static int deleteRun(DataSource db, JedisPool pool, String runId) {
        return delete(db, pool, "username LIKE ? ESCAPE '!'", "ck!_" + runId + "!_%");
    }

    /**
     * Every leftover synthetic user, from a crashed or kept run, without sessions: deleted in Postgres,
     * then {@code sub:built} is deleted so the sets are rebuilt from Postgres without them.
     */
    public static int cleanAll(DataSource db, JedisPool pool) {
        int n = delete(db, pool, "username ~ ?", CHECKER_NAME);
        try (Jedis jedis = pool.getResource()) {
            jedis.del(RedisKeys.SUB_BUILT);
        }
        return n;
    }

    private static int delete(DataSource db, JedisPool pool, String where, String arg) {
        List<String> ids = new ArrayList<>();
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM users WHERE " + where)) {
                    ps.setString(1, arg);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) ids.add(rs.getString(1));
                    }
                }
                Array arr = Snapshots.uuids(c, ids);
                for (String sql : List.of("DELETE FROM notifications WHERE user_id = ANY(?)",
                        "DELETE FROM notification_reads WHERE user_id = ANY(?)",
                        "DELETE FROM users WHERE id = ANY(?)")) {
                    try (PreparedStatement ps = c.prepareStatement(sql)) {
                        ps.setArray(1, arr);
                        ps.executeUpdate();
                    }
                }
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("deleting checker users failed", e);
        }
        if (!ids.isEmpty()) {
            try (Jedis jedis = pool.getResource()) {
                jedis.del(ids.stream().map(id -> RedisKeys.UNREAD_PREFIX + id).toArray(String[]::new));
            }
        }
        return ids.size();
    }
}

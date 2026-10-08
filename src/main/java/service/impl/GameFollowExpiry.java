package service.impl;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A game follow expires a day after its game ends (API {@code expiresAt}; spec 5.5). Deletes go
 * through {@link FollowService}, the normal save path, so the follow also leaves its sub:* sets.
 */
public final class GameFollowExpiry {

    private static final Set<String> ENDED = Set.of("FINAL", "POSTPONED", "CANCELED");
    private static final Duration AFTER_END = Duration.ofDays(1);
    /** Game records expire after 8 idle days; a follow older than that with no record has nothing left to follow. */
    private static final Duration RECORD_GONE = Duration.ofDays(8);

    private final DataSource db;
    private final JedisPool pool;
    private final FollowService follows;

    public GameFollowExpiry(DataSource db, JedisPool pool, FollowService follows) {
        this.db = db;
        this.pool = pool;
        this.follows = follows;
    }

    /** Marks ended games' follows to expire and deletes expired ones. Returns how many it deleted. */
    public int runOnce(Instant now) {
        List<String[]> toDelete = new ArrayList<>();   // {userId, followId}
        try (Connection c = db.getConnection(); Jedis jedis = pool.getResource()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, user_id, target_id, created_at FROM follows WHERE kind = 'game' AND expires_at IS NULL");
                 PreparedStatement mark = c.prepareStatement(
                         "UPDATE follows SET expires_at = ? WHERE id = ? AND expires_at IS NULL");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, String> record = jedis.hgetAll(RedisKeys.GAME_PREFIX + rs.getString("target_id"));
                    if (record.isEmpty()) {
                        if (rs.getTimestamp("created_at").toInstant().isBefore(now.minus(RECORD_GONE))) {
                            toDelete.add(new String[] {rs.getString("user_id"), rs.getString("id")});
                        }
                    } else if (ENDED.contains(record.get("status"))) {
                        mark.setTimestamp(1, Timestamp.from(now.plus(AFTER_END)));
                        mark.setObject(2, UUID.fromString(rs.getString("id")));
                        mark.executeUpdate();
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, user_id FROM follows WHERE kind = 'game' AND expires_at <= ?")) {
                ps.setTimestamp(1, Timestamp.from(now));
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) toDelete.add(new String[] {rs.getString("user_id"), rs.getString("id")});
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("expiring game follows failed", e);
        }
        int deleted = 0;
        for (String[] f : toDelete) {
            if (follows.delete(f[0], f[1]).status() == 204) deleted++;
        }
        return deleted;
    }

    public void start(ScheduledExecutorService scheduler) {
        scheduler.scheduleWithFixedDelay(() -> {
            try {
                runOnce(Instant.now());
            } catch (RuntimeException e) {
                System.err.println("[GameFollowExpiry] failed, next hour retries: " + e.getMessage());
            }
        }, 1, 60, TimeUnit.MINUTES);
    }
}

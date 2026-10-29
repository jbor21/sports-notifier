package service.impl;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.util.Locale;

/**
 * Keeps {@code synthetic:users} as user-pref creates the checker's users (#47). Only the checker can
 * create a {@code ck_} name (the gateway asks for its key, #51), so the prefix is proof enough here.
 */
public final class SyntheticUsers {

    private static final String PREFIX = "ck_";

    private final JedisPool pool;
    private final Runnable requestRebuild;

    /** {@code requestRebuild}: what to do when Redis can't be written; the rebuild refills the set from Postgres. */
    public SyntheticUsers(JedisPool pool, Runnable requestRebuild) {
        this.pool = pool;
        this.requestRebuild = requestRebuild;
    }

    /** The same trim and lowercase as signup. */
    public static boolean isSynthetic(String username) {
        return username != null && username.trim().toLowerCase(Locale.ROOT).startsWith(PREFIX);
    }

    /** The user is already committed in Postgres; a failed Redis write only means the set must be rebuilt. */
    public void created(String userId, String username) {
        if (!isSynthetic(username)) return;
        try (Jedis jedis = pool.getResource()) {
            jedis.sadd(RedisKeys.SYNTHETIC_USERS, userId);
        } catch (RuntimeException e) {
            System.err.println("[SyntheticUsers] Redis update failed, asking for a rebuild: " + e.getMessage());
            requestRebuild.run();
        }
    }
}

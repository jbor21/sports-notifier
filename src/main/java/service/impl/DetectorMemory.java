package service.impl;

import alerts.GameMemory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.util.List;

/**
 * Stage 1's memory of each game, {@code det:<gameId>}: the last play version handled and what the
 * detectors remember (Phase 3.3 spec, 2.2). It replaces 3.1's {@code s1:last:<gameId>}; the version
 * field is the same duplicate check.
 */
public class DetectorMemory {

    /** Outlives game-events' 7-day retention, so re-reading the whole topic can't re-send old plays. */
    static final long TTL_SECONDS = 8 * 86_400L;

    /**
     * Set if higher, as one script. A worker that stalled and lost its lanes in a rebalance can wake up
     * and finish an older play after the new owner finished newer ones; it must not put older memory back.
     */
    private static final String WRITE_IF_HIGHER =
        "local cur = tonumber(redis.call('HGET', KEYS[1], 'version')) or 0\n" +
        "if tonumber(ARGV[1]) <= cur then return 0 end\n" +
        "redis.call('HSET', KEYS[1], 'version', ARGV[1], 'memory', ARGV[2])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[3])\n" +
        "return 1\n";

    /** @param memory null when the game has none: its next play is the baseline */
    public record Stored(long version, GameMemory memory) {}

    private final JedisPool pool;

    public DetectorMemory(JedisPool pool) {
        this.pool = pool;
    }

    public Stored read(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            List<String> v = jedis.hmget(RedisKeys.DETECTOR_PREFIX + gameId, "version", "memory");
            long version = v.get(0) == null ? 0L : Long.parseLong(v.get(0));
            return new Stored(version, v.get(1) == null ? null : GameMemory.fromJson(v.get(1)));
        }
    }

    /** Drops a game's memory, so its next play starts from a baseline (unreadable memory, issue #15). */
    public void forget(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(RedisKeys.DETECTOR_PREFIX + gameId);
        }
    }

    /** @return true if {@code version} was higher and the memory is now stored */
    public boolean writeIfHigher(String gameId, long version, GameMemory memory) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(WRITE_IF_HIGHER, List.of(RedisKeys.DETECTOR_PREFIX + gameId),
                List.of(String.valueOf(version), memory.toJson(), String.valueOf(TTL_SECONDS)));
            return Long.valueOf(1L).equals(r);
        }
    }
}

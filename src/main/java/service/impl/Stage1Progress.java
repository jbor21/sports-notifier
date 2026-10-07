package service.impl;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.util.List;

/**
 * The last play version Stage 1 finished for each game ({@code s1:last:<gameId>}). A play at or below
 * it is a duplicate: the retry loop sent it twice, or a worker re-read it after a crash.
 */
public class Stage1Progress {

    /** Outlives game-events' 7-day retention, so re-reading the whole topic can't re-send old plays. */
    static final long TTL_SECONDS = 8 * 86_400L;

    /**
     * Set if higher, as one script. A worker that stalled and lost its lanes in a rebalance can wake
     * up and finish an older play after the new owner finished newer ones; a plain SET would lower
     * the value, and the newer plays could then be sent again.
     */
    private static final String RECORD_IF_HIGHER =
        "local cur = tonumber(redis.call('GET', KEYS[1])) or 0\n" +
        "if tonumber(ARGV[1]) <= cur then return 0 end\n" +
        "redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])\n" +
        "return 1\n";

    private final JedisPool pool;

    public Stage1Progress(JedisPool pool) {
        this.pool = pool;
    }

    public long last(String gameId) {
        try (Jedis jedis = pool.getResource()) {
            String v = jedis.get(RedisKeys.STAGE1_LAST_PREFIX + gameId);
            return v == null ? 0L : Long.parseLong(v);
        }
    }

    /** @return true if {@code version} was higher and is now recorded */
    public boolean recordIfHigher(String gameId, long version) {
        try (Jedis jedis = pool.getResource()) {
            Object r = jedis.eval(RECORD_IF_HIGHER, List.of(RedisKeys.STAGE1_LAST_PREFIX + gameId),
                List.of(String.valueOf(version), String.valueOf(TTL_SECONDS)));
            return Long.valueOf(1L).equals(r);
        }
    }
}

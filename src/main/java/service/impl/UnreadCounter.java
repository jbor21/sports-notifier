package service.impl;

import com.google.gson.Gson;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.util.List;

/**
 * The Notification service's side of the unread badge (Phase 3.4 spec, 8.2): after "seen" or "clear"
 * commits in Postgres, the count is 0, and every tab of the user, on every gateway, is told so.
 */
public final class UnreadCounter {

    static final long TTL_SECONDS = 86_400L;

    /**
     * KEYS[1] = unread:<userId>   KEYS[2] = conn:<userId>
     * ARGV[1] = TTL seconds   ARGV[2] = gateway message   ARGV[3] = gateway channel prefix
     * One script, so the reset and the publishes go together.
     */
    private static final String RESET_SCRIPT =
        "redis.call('SET', KEYS[1], '0', 'EX', ARGV[1])\n" +
        "local now = tonumber(redis.call('TIME')[1])\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)\n" +
        "for _, gw in ipairs(redis.call('ZRANGE', KEYS[2], 0, -1)) do\n" +
        "  redis.call('PUBLISH', ARGV[3] .. gw, ARGV[2])\n" +
        "end\n" +
        "return 1\n";

    private static final Gson GSON = new Gson();

    private final JedisPool pool;

    public UnreadCounter(JedisPool pool) {
        this.pool = pool;
    }

    public void reset(String userId) {
        String message = "{\"userId\":" + GSON.toJson(userId) + ",\"payload\":{\"type\":\"unread_count\",\"count\":0}}";
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(RESET_SCRIPT,
                List.of(RedisKeys.UNREAD_PREFIX + userId, RedisKeys.CONN_PREFIX + userId),
                List.of(String.valueOf(TTL_SECONDS), message, RedisKeys.GATEWAY_CHANNEL_PREFIX));
        }
    }
}

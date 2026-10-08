package gateway;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;
import util.RedisKeys;

import java.util.function.Function;

/**
 * The count {@code hello} carries: {@code unread:<userId>} when Redis has it, otherwise Postgres's
 * count (through the Notification service), stored with SET NX so it doesn't overwrite a value that
 * appeared meanwhile (Phase 3.4 spec, 8.2).
 */
public final class UnreadCount {

    static final long TTL_SECONDS = 86_400L;

    private final JedisPool pool;
    private final Function<String, Integer> fromPostgres;

    public UnreadCount(JedisPool pool, Function<String, Integer> fromPostgres) {
        this.pool = pool;
        this.fromPostgres = fromPostgres;
    }

    public int get(String userId) {
        String key = RedisKeys.UNREAD_PREFIX + userId;
        try (Jedis jedis = pool.getResource()) {
            String cached = jedis.get(key);
            if (cached != null) return Integer.parseInt(cached);
        }
        int counted = fromPostgres.apply(userId);
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key, String.valueOf(counted), SetParams.setParams().nx().ex(TTL_SECONDS));
            String now = jedis.get(key);
            return now == null ? counted : Integer.parseInt(now);
        }
    }
}

package gateway;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;
import util.RedisPipelines;

import java.util.Collection;
import java.util.List;

/**
 * Records which gateways hold a user's sockets, so Stage 2 publishes only to those gateways instead
 * of every gateway reading every notification (DESIGN.md decision 13).
 *
 * {@code conn:<userId>} is a sorted set: member = gateway id, score = when that entry expires. A set,
 * because one user can be on two gateways at once (a laptop and a phone). Expiry is per entry: a
 * gateway renews its users every 30 s for 90 s, so it can miss two renewals and keep them, and a
 * gateway that dies without cleaning up is forgotten within a minute and a half. Stage 2's script
 * skips and removes expired entries. The key itself also expires after 90 s without a renewal, so a
 * user who never comes back leaves nothing behind. Times come from Redis's own clock (TIME), so pods
 * whose clocks drift can't expire each other's entries early.
 */
public final class ConnectionRegistry {

    /** KEYS[1] = conn:<userId>   ARGV[1] = gateway id   ARGV[2] = ttl seconds */
    private static final String ADD_SCRIPT =
        "local now = tonumber(redis.call('TIME')[1])\n" +
        "redis.call('ZADD', KEYS[1], now + tonumber(ARGV[2]), ARGV[1])\n" +
        "redis.call('EXPIRE', KEYS[1], ARGV[2])\n" +
        "return 1\n";

    private final JedisPool pool;
    private final String gatewayId;
    private final int entryTtlSeconds;

    public ConnectionRegistry(JedisPool pool, String gatewayId, int entryTtlSeconds) {
        this.pool = pool;
        this.gatewayId = gatewayId;
        this.entryTtlSeconds = entryTtlSeconds;
    }

    /** A socket for this user opened on this gateway. */
    public void add(String userId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.eval(ADD_SCRIPT, List.of(RedisKeys.CONN_PREFIX + userId), List.of(gatewayId, String.valueOf(entryTtlSeconds)));
        }
    }

    /** The user's last socket on this gateway closed. */
    public void remove(String userId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.zrem(RedisKeys.CONN_PREFIX + userId, gatewayId);
        }
    }

    /** Pushes every connected user's expiry out again; one round trip for all of them. */
    public void renewAll(Collection<String> userIds) {
        if (userIds.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (String userId : userIds) {
                p.eval(ADD_SCRIPT, List.of(RedisKeys.CONN_PREFIX + userId), List.of(gatewayId, String.valueOf(entryTtlSeconds)));
            }
            RedisPipelines.syncChecked(p);
        }
    }

    /** Graceful shutdown: this gateway stops holding anyone, before it closes their sockets. */
    public void removeAll(Collection<String> userIds) {
        if (userIds.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (String userId : userIds) p.zrem(RedisKeys.CONN_PREFIX + userId, gatewayId);
            RedisPipelines.syncChecked(p);
        }
    }
}

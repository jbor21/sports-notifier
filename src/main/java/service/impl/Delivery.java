package service.impl;

import com.google.gson.Gson;
import model.Notification;
import model.NotificationMessage;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;

import java.util.List;

/**
 * Delivers one notification exactly once in effect (Phase 3.1 spec, section 2.3).
 *
 * Kafka delivers at least once, so Stage 2 will sometimes see a message twice. With separate steps,
 * the order of "mark done" and "deliver" picks the failure:
 *
 * <pre>
 *   mark, then deliver   crash in between: the retry sees "done"   the notification is lost
 *   deliver, then mark   crash in between: the retry delivers again  a duplicate
 * </pre>
 *
 * One Lua script does all of it: claim the dedup key, add the history entry, and publish to every
 * gateway holding the user's socket. Redis runs the script as one step, so all three happen or none
 * do, and a redelivered message finds the key and does nothing. At-least-once Kafka plus this
 * script is exactly once in effect, which is what the Phase 4 checker tests.
 *
 * The publish to the gateway is Redis pub/sub, fire-and-forget: a gateway that dies at that instant
 * misses the live push, and history still has the notification.
 */
public class Delivery {

    private static final Gson GSON = new Gson();
    /** Outlives the notifications topic's 1-day retention, so re-reading the topic delivers nothing twice. */
    static final long DEDUP_TTL_SECONDS = 2 * 86_400L;
    private static final int MAX_HISTORY_INDEX = 999;   // keep the newest 1000

    /**
     * KEYS[1] = notif:<messageId>:<userId>   KEYS[2] = notifications:<userId>   KEYS[3] = conn:<userId>
     * ARGV[1] = history entry JSON   ARGV[2] = dedup TTL seconds   ARGV[3] = last history index
     * ARGV[4] = gateway message JSON ({"userId", "payload"})   ARGV[5] = gateway channel prefix
     *
     * conn:<userId> scores are expiry times in seconds from Redis's TIME; past ones are removed here.
     * Returns 1 delivered, 0 already delivered.
     */
    private static final String DELIVER_SCRIPT =
        "if not redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[2]) then return 0 end\n" +
        "redis.call('LPUSH', KEYS[2], ARGV[1])\n" +
        "redis.call('LTRIM', KEYS[2], 0, ARGV[3])\n" +
        "local now = tonumber(redis.call('TIME')[1])\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', now)\n" +
        "for _, gw in ipairs(redis.call('ZRANGE', KEYS[3], 0, -1)) do\n" +
        "  redis.call('PUBLISH', ARGV[5] .. gw, ARGV[4])\n" +
        "end\n" +
        "return 1\n";

    private final JedisPool pool;

    public Delivery(JedisPool pool) {
        this.pool = pool;
    }

    /** @return true if delivered now, false if it had already been delivered */
    public boolean deliver(NotificationMessage m) {
        try (Jedis jedis = pool.getResource()) {
            return Long.valueOf(1L).equals(jedis.eval(DELIVER_SCRIPT, keys(m), args(m)));
        }
    }

    /** One script per message, pipelined: one round trip, and Redis runs them in this order. */
    public void deliverAll(List<NotificationMessage> batch) {
        if (batch.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (NotificationMessage m : batch) p.eval(DELIVER_SCRIPT, keys(m), args(m));
            p.sync();
        }
    }

    private static List<String> keys(NotificationMessage m) {
        return List.of(
            RedisKeys.NOTIF_DEDUP_PREFIX + m.getMessageId() + ":" + m.getUserId(),
            RedisKeys.NOTIFICATIONS_PREFIX + m.getUserId(),
            RedisKeys.CONN_PREFIX + m.getUserId());
    }

    private static List<String> args(NotificationMessage m) {
        // The history entry keeps the course Notification shape, so the course UI still reads history.
        String entry = GSON.toJson(new Notification(m.getUserId(), m.getGameId(), m.getMessage(),
            m.getDetectedAt(), m.getLeague(), m.getHomeTeam(), m.getAwayTeam()));
        String toGateway = "{\"userId\":" + GSON.toJson(m.getUserId()) + ",\"payload\":" + entry + "}";
        return List.of(entry, String.valueOf(DEDUP_TTL_SECONDS), String.valueOf(MAX_HISTORY_INDEX),
            toGateway, RedisKeys.GATEWAY_CHANNEL_PREFIX);
    }
}

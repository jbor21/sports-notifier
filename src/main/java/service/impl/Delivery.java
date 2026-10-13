package service.impl;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import model.NotificationMessage;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import util.RedisKeys;
import util.RedisPipelines;

import java.time.Instant;
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
 * One Lua script does all of it: check the dedup key, publish to every gateway holding the user's
 * socket, and only then claim the key. History is not here: the history writers, a separate consumer
 * group, save it to Postgres, so a slow Postgres never delays a push (Phase 3.2 spec, 7.1). Redis runs the script as one step,
 * so nothing can land in between, and a redelivered message finds the key and does nothing. The
 * claim is last because Redis does not undo a script that fails halfway: a claim written before a
 * failing write would make the retry believe the message was delivered. At-least-once Kafka plus this
 * script is exactly once in effect, which is what the Phase 4 checker tests.
 *
 * The publish to the gateway is Redis pub/sub, fire-and-forget: a gateway that dies at that instant
 * misses the live push, and history in Postgres still has the notification.
 */
public class Delivery {

    private static final Gson GSON = new Gson();
    /** Outlives the notifications topic's 1-day retention, so re-reading the topic delivers nothing twice. */
    static final long DEDUP_TTL_SECONDS = 2 * 86_400L;

    /**
     * KEYS[1] = notif:<messageId>:<userId>   KEYS[2] = conn:<userId>   KEYS[3] = unread:<userId>
     * ARGV[1] = dedup TTL seconds   ARGV[2] = gateway message JSON ({"userId", "payload"}), or "" for a
     *           message with no API body (nothing to show)   ARGV[3] = gateway channel prefix
     * ARGV[4] = the user id as a JSON string
     *
     * conn:<userId> scores are expiry times in seconds from Redis's TIME; past ones are removed here.
     *
     * The unread badge (Phase 3.4 spec, 8.2) is counted here, in the same step as the claim, so a push
     * is counted exactly once however often Kafka redelivers it. Only an existing counter is raised:
     * INCR on a missing key would start at 1 instead of the real count, so a missing counter waits for
     * the gateway to fill it from Postgres. The new count goes to the same gateways, after the push.
     *
     * The claim holds when the push was published, in epoch milliseconds from Redis's own TIME, so
     * every delivery time comes from one clock. The checker uses it to tell a push lost on an open
     * socket from one published while that socket was down (Phase 4.2 spec, 7.1). The dedup check is
     * only EXISTS, so a claim written as '1' before this still dedups.
     *
     * Returns 1 delivered, 0 already delivered.
     */
    private static final String DELIVER_SCRIPT =
        "if redis.call('EXISTS', KEYS[1]) == 1 then return 0 end\n" +
        "local t = redis.call('TIME')\n" +
        "local now = tonumber(t[1])\n" +
        "redis.call('ZREMRANGEBYSCORE', KEYS[2], '-inf', now)\n" +
        "local gateways = redis.call('ZRANGE', KEYS[2], 0, -1)\n" +
        "if ARGV[2] ~= '' then\n" +
        "  for _, gw in ipairs(gateways) do redis.call('PUBLISH', ARGV[3] .. gw, ARGV[2]) end\n" +
        "end\n" +
        "if redis.call('EXISTS', KEYS[3]) == 1 then\n" +
        "  local n = redis.call('INCR', KEYS[3])\n" +
        "  local count = '{\"userId\":' .. ARGV[4] .. ',\"payload\":{\"type\":\"unread_count\",\"count\":' .. n .. '}}'\n" +
        "  for _, gw in ipairs(gateways) do redis.call('PUBLISH', ARGV[3] .. gw, count) end\n" +
        "end\n" +
        "redis.call('SET', KEYS[1], now * 1000 + math.floor(tonumber(t[2]) / 1000), 'EX', ARGV[1])\n" +
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

    /**
     * One script per message, pipelined: one round trip, and Redis runs them in this order.
     * Throws on any error reply, so Stage 2 rewinds and retries the batch instead of committing it.
     */
    public void deliverAll(List<NotificationMessage> batch) {
        if (batch.isEmpty()) return;
        try (Jedis jedis = pool.getResource()) {
            Pipeline p = jedis.pipelined();
            for (NotificationMessage m : batch) p.eval(DELIVER_SCRIPT, keys(m), args(m));
            RedisPipelines.syncChecked(p);
        }
    }

    private static List<String> keys(NotificationMessage m) {
        return List.of(
            RedisKeys.NOTIF_DEDUP_PREFIX + m.getMessageId() + ":" + m.getUserId(),
            RedisKeys.CONN_PREFIX + m.getUserId(),
            RedisKeys.UNREAD_PREFIX + m.getUserId());
    }

    /** The socket gets the API's notification message: Stage 1's body plus createdAt, as history serves it. */
    private static List<String> args(NotificationMessage m) {
        String toGateway = "";
        if (m.getNotification() != null) {
            JsonObject body = m.getNotification().deepCopy();
            long created = m.getCreatedAt() > 0 ? m.getCreatedAt() : System.currentTimeMillis();
            body.addProperty("createdAt", Instant.ofEpochMilli(created).toString());
            JsonObject payload = new JsonObject();
            payload.addProperty("type", "notification");
            payload.add("notification", body);
            JsonObject message = new JsonObject();
            message.addProperty("userId", m.getUserId());
            message.add("payload", payload);
            toGateway = message.toString();
        }
        return List.of(String.valueOf(DEDUP_TTL_SECONDS), toGateway, RedisKeys.GATEWAY_CHANNEL_PREFIX,
            GSON.toJson(m.getUserId()));
    }
}

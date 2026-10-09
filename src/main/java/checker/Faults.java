package checker;

import com.google.gson.JsonObject;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import util.RedisKeys;

import java.util.List;

/**
 * The self-test (Phase 4.1 spec, 6.4): a checker that always passes proves nothing, so each fault
 * breaks one user's run through the real paths and the checker must report exactly that.
 */
public final class Faults {

    private Faults() {}

    /** The finding a fault must produce. */
    public static String primaryKind(String fault) {
        return switch (fault) {
            case "srem" -> "history_missing";
            case "dup" -> "socket_duplicate";
            case "follow" -> "follow_changed";
            default -> throw new IllegalArgumentException("unknown FAULT " + fault + " (srem, dup, follow)");
        };
    }

    /**
     * A fault run passes when its primary finding is there and every finding is about the faulted user.
     * (A lost Redis write also shows on that user's sockets, and can reshape a combined push.)
     */
    public static boolean passes(String fault, String userId, List<Finding> findings) {
        String kind = primaryKind(fault);
        return findings.stream().anyMatch(f -> f.kind().equals(kind))
                && findings.stream().allMatch(f -> userId.equals(f.userId()));
    }

    /** A lost Redis write: the user drops out of one switch set the follow still has on in Postgres. */
    public static void srem(JedisPool pool, String key, String userId) {
        try (Jedis jedis = pool.getResource()) {
            jedis.srem(key, userId);
        }
    }

    /** Publishes a push the user already got once more, on every gateway holding their socket. */
    public static void duplicate(JedisPool pool, String userId, JsonObject notification) {
        JsonObject payload = new JsonObject();
        payload.addProperty("type", "notification");
        payload.add("notification", notification);
        JsonObject message = new JsonObject();
        message.addProperty("userId", userId);
        message.add("payload", payload);
        try (Jedis jedis = pool.getResource()) {
            for (String gw : jedis.zrange(RedisKeys.CONN_PREFIX + userId, 0, -1)) {
                jedis.publish(RedisKeys.GATEWAY_CHANNEL_PREFIX + gw, message.toString());
            }
        }
    }
}

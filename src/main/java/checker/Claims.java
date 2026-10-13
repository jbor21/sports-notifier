package checker;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.Pipeline;
import redis.clients.jedis.Response;
import util.RedisKeys;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** When Stage 2 delivered each push: the time its claim ({@code notif:<messageId>:<userId>}) holds. */
public final class Claims {

    private static final int CHUNK = 1000;

    private Claims() {}

    /** By {@link Verifier#key}; a push with no claim, or a claim that is not a time (written as '1' before 4.2), is left out. */
    public static Map<String, Long> deliveredAt(JedisPool pool, Collection<ExpectedPush> pushes) {
        Map<String, Long> out = new HashMap<>();
        List<ExpectedPush> all = new ArrayList<>(pushes);
        try (Jedis jedis = pool.getResource()) {
            for (int from = 0; from < all.size(); from += CHUNK) {
                List<ExpectedPush> chunk = all.subList(from, Math.min(all.size(), from + CHUNK));
                Pipeline p = jedis.pipelined();
                List<Response<String>> values = new ArrayList<>();
                for (ExpectedPush e : chunk) values.add(p.get(RedisKeys.NOTIF_DEDUP_PREFIX + e.messageId() + ":" + e.userId()));
                p.sync();
                for (int i = 0; i < chunk.size(); i++) {
                    Long at = parse(values.get(i).get());
                    if (at != null) out.put(Verifier.key(chunk.get(i).userId(), chunk.get(i).messageId()), at);
                }
            }
        }
        return out;
    }

    private static Long parse(String value) {
        if (value == null) return null;
        try {
            long at = Long.parseLong(value);
            return at > 1_000_000_000_000L ? at : null;   // '1' from before claims held a time
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

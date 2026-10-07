package util;

import redis.clients.jedis.Pipeline;
import redis.clients.jedis.exceptions.JedisDataException;

/** Pipeline helpers. */
public final class RedisPipelines {

    private RedisPipelines() {}

    /**
     * Sends the pipeline and throws the first error reply. Jedis's {@code sync()} keeps each reply's
     * error inside its Response and never looks, so a failed EVAL or SINTERSTORE in a pipeline would
     * otherwise pass silently: a Stage 2 batch would be committed with nothing delivered.
     */
    public static void syncChecked(Pipeline p) {
        for (Object reply : p.syncAndReturnAll()) {
            if (reply instanceof JedisDataException e) throw e;
        }
    }
}

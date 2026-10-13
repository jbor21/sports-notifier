package checker;

import redis.clients.jedis.Jedis;

import java.util.List;
import java.util.function.LongSupplier;

/**
 * How far Redis's clock is from the checker's. Delivery times come from Redis (Stage 2's script), socket
 * times from the checker; on EKS they run on different machines (Phase 4.2 spec, 7.1).
 */
public final class RedisClock {

    private RedisClock() {}

    /** Redis time minus local time, in ms, taking the local time halfway through the round trip. */
    public static long offsetMillis(Jedis jedis, LongSupplier localMillis) {
        long before = localMillis.getAsLong();
        List<String> t = jedis.time();
        long after = localMillis.getAsLong();
        long redis = Long.parseLong(t.get(0)) * 1000 + Long.parseLong(t.get(1)) / 1000;
        return redis - (before + after) / 2;
    }
}
